import { useEffect, useRef, useState } from "preact/hooks";
import { ApiError } from "../api/client";
import {
  deleteMail,
  deleteOldMail,
  getMailStatus,
  importEml,
  setMailInterval,
  listMail,
  type Mail,
  type MailStatus,
  startMailReread,
  startMailSync,
} from "../api/mail";
import { Icon } from "../components/Icon";
import { MailList } from "../components/MailList";
import { Toast } from "../components/Toast";
import {
  DEFAULT_SYNC_MINUTES,
  formatClock,
  intervalPhrase,
  intervalStepIndex,
  intervalTick,
  REREAD_CONFIRM,
  SYNC_INTERVAL_STEPS,
  SYNC_POLL_MS,
  syncSummary,
} from "../domain/mail";
import { formatDate } from "./columns";

/** The slider saves this long after the last move, so dragging over several steps sends one request. */
const INTERVAL_SAVE_DELAY_MS = 400;

const addMinutes = (iso: string, minutes: number): string => new Date(new Date(iso).getTime() + minutes * 60_000).toISOString();

/**
 * Mail (T-086, T-087): employers' answers and other job mail from your mailbox, each linked to its application, with
 * suggested status changes to accept or dismiss. "Check mail now" reads the folders at once; an .eml file can be
 * imported by hand.
 */
export function MailScreen({ dataVersion = 0 }: { dataVersion?: number }) {
  const [items, setItems] = useState<Mail[]>([]);
  const [status, setStatus] = useState<MailStatus | null>(null);
  const [pendingOnly, setPendingOnly] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // The mailbox's own error from the last read: kept apart from `error`, which every reload of the list clears.
  const [syncError, setSyncError] = useState<string | null>(null);
  const [toast, setToast] = useState<string | null>(null);
  const [reload, setReload] = useState(0);
  const [selected, setSelected] = useState<ReadonlySet<string>>(new Set());
  const [olderDays, setOlderDays] = useState(14);

  useEffect(() => {
    const controller = new AbortController();
    Promise.all([listMail({ pending: pendingOnly }, controller.signal), getMailStatus(controller.signal)])
      .then(([list, s]) => {
        setItems(list);
        // Keep only the choices that are still on the screen.
        setSelected((prev) => new Set(list.filter((m) => prev.has(m.id)).map((m) => m.id)));
        setStatus(s);
        setError(null);
      })
      .catch((e: unknown) => {
        if (!controller.signal.aborted) {
          setError(e instanceof ApiError ? e.message : "Could not load the mail.");
        }
      });
    return () => controller.abort();
  }, [pendingOnly, reload, dataVersion]);

  // A read of the mailbox runs in the background (started here, or by the schedule): ask until it is done, then show
  // what it found and load the new mail.
  const wasSyncing = useRef(false);
  useEffect(() => {
    if (!status?.syncing) {
      if (wasSyncing.current) {
        wasSyncing.current = false;
        const r = status?.lastResult;
        if (r?.error) {
          setSyncError(r.error);
        } else if (r) {
          setToast(syncSummary(r));
        }
        setReload((n) => n + 1);
      }
      return undefined;
    }
    wasSyncing.current = true;
    const timer = setTimeout(() => {
      getMailStatus().then(setStatus).catch(() => undefined);
    }, SYNC_POLL_MS);
    return () => clearTimeout(timer);
  }, [status]);

  const run = async (action: () => Promise<string>) => {
    setBusy(true);
    setError(null);
    try {
      setToast(await action());
      setReload((n) => n + 1);
    } catch (e: unknown) {
      setError(e instanceof ApiError ? e.message : "That did not work.");
    } finally {
      setBusy(false);
    }
  };

  /** Starts a read in the background (a plain one, or a re-read) and follows it; a read already running is just followed. */
  const startRead = (start: () => Promise<unknown>) =>
    run(async () => {
      setSyncError(null);
      try {
        await start();
      } catch (e: unknown) {
        // 409: a read is already running; just follow it.
        if (!(e instanceof ApiError && e.status === 409)) {
          throw e;
        }
      }
      setStatus(await getMailStatus());
      return "Checking the mailbox…";
    });

  const reread = () => {
    if (window.confirm(REREAD_CONFIRM)) {
      void startRead(startMailReread);
    }
  };

  const toggle = (id: string) =>
    setSelected((prev) => {
      const next = new Set(prev);
      if (!next.delete(id)) {
        next.add(id);
      }
      return next;
    });

  const removeSelected = () => {
    if (selected.size === 0 || !window.confirm(`Delete ${selected.size} mail${selected.size === 1 ? "" : "s"} from this app? Your mailbox is not touched.`)) {
      return;
    }
    run(async () => `Deleted ${(await deleteMail([...selected])).deleted} mails.`);
  };

  const removeOld = () => {
    const days = Math.floor(olderDays);
    if (!(days >= 1) || !window.confirm(`Delete every stored mail older than ${days} days from this app? Your mailbox is not touched.`)) {
      return;
    }
    run(async () => `Deleted ${(await deleteOldMail(days)).deleted} mails older than ${days} days.`);
  };

  // The slider shows its own position while the choice is saved, and goes back to the saved one when saving fails.
  const [pickedStep, setPickedStep] = useState<number | null>(null);
  const [intervalError, setIntervalError] = useState<string | null>(null);
  const saveTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  useEffect(() => () => {
    if (saveTimer.current) {
      clearTimeout(saveTimer.current);
    }
  }, []);

  const pickInterval = (step: number) => {
    setPickedStep(step);
    setIntervalError(null);
    if (saveTimer.current) {
      clearTimeout(saveTimer.current);
    }
    saveTimer.current = setTimeout(() => {
      const minutes = SYNC_INTERVAL_STEPS[step];
      setMailInterval(minutes)
        .then((saved) => {
          setStatus((s) => (s ? { ...s, intervalMinutes: saved.intervalMinutes, nextCheck: s.lastSync ? addMinutes(s.lastSync, saved.intervalMinutes) : null } : s));
          setPickedStep(null);
          setToast(`Saved: reading the mailbox ${intervalPhrase(saved.intervalMinutes)}.`);
        })
        .catch((e: unknown) => {
          setPickedStep(null);
          setIntervalError(e instanceof ApiError ? e.message : "Could not save how often the mailbox is read.");
        });
    }, INTERVAL_SAVE_DELAY_MS);
  };

  const syncing = !!status?.syncing;
  const savedMinutes = status?.intervalMinutes ?? DEFAULT_SYNC_MINUTES;
  const step = pickedStep ?? intervalStepIndex(savedMinutes);
  const shownMinutes = SYNC_INTERVAL_STEPS[step];
  return (
    <section aria-labelledby="mail-title" class="stack mail-screen">
      <div class="toolbar">
        <h1 id="mail-title">Mail</h1>
        <span class="grow" />
        {status && status.pending > 0 && <span class="badge num">{status.pending} to decide</span>}
        <button class="chip" type="button" aria-pressed={pendingOnly} onClick={() => setPendingOnly((v) => !v)}>
          Only suggestions
        </button>
        <label class="btn file-button">
          <Icon name="upload" />
          Import .eml
          <input
            class="sr-only"
            type="file"
            aria-label="Email file to import"
            accept=".eml,message/rfc822"
            disabled={busy}
            onChange={(e) => {
              const file = (e.target as HTMLInputElement).files?.[0];
              (e.target as HTMLInputElement).value = "";
              if (file) {
                run(async () => {
                  const r = await importEml(file);
                  return r.stored ? `Imported: ${r.correspondence?.subject ?? "mail"}.` : `Not stored: ${r.reason}.`;
                });
              }
            }}
          />
        </label>
        <button
          class="btn"
          type="button"
          disabled={busy || !status?.configured || syncing}
          title={status?.configured ? "Forget which mails were read and read the mailbox again" : "Set up IMAP first (docs/deployment.md)"}
          onClick={reread}
        >
          <Icon name="history" />
          Re-read mail
        </button>
        <button
          class={`btn primary${syncing ? " is-refreshing" : ""}`}
          type="button"
          disabled={busy || !status?.configured || syncing}
          title={status?.configured ? undefined : "Set up IMAP first (docs/deployment.md)"}
          aria-busy={syncing}
          onClick={() => void startRead(startMailSync)}
        >
          <Icon name="refresh" spin={syncing} />
          {syncing ? "Checking…" : "Check mail now"}
        </button>
      </div>

      {status && !status.configured && (
        <div class="notice">
          The mailbox is not connected. Set the IMAP settings (see docs/deployment.md) to track answers automatically, or import
          single emails as .eml files.
        </div>
      )}
      {status?.configured && (
        <div class={`sync-card${syncing ? " is-syncing" : ""}`}>
          <span class="sync-icon" aria-hidden="true">
            <Icon name={syncing ? "refresh" : "mail"} spin={syncing} />
          </span>
          <div class="sync-text" role="status" aria-live="polite">
            <strong>{syncing ? "Checking the mailbox…" : "Mailbox connected"}</strong>
            <span class="muted">
              Reading {status.folders.join(", ")} {intervalPhrase(savedMinutes)}, read-only. Only job mail is kept.
              {status.lastSync && <> Last check {formatDate(status.lastSync)}.</>}
              {status.nextCheck && !syncing && <> Next check {formatClock(status.nextCheck)}.</>}
              {status.lastError && <> Last error: {status.lastError}</>}
            </span>
          </div>
          <div class="sync-interval">
            <label class="sync-interval-label" for="sync-interval">How often to read</label>
            <input
              id="sync-interval"
              class="slider"
              type="range"
              min={0}
              max={SYNC_INTERVAL_STEPS.length - 1}
              step={1}
              value={step}
              aria-label="How often to read the mailbox"
              aria-valuetext={intervalPhrase(shownMinutes)}
              onInput={(e) => pickInterval(Number(e.currentTarget.value))}
            />
            <div class="slider-ticks" aria-hidden="true">
              {SYNC_INTERVAL_STEPS.map((m, i) => (
                <span key={m} class={i === step ? "is-current" : undefined}>{intervalTick(m)}</span>
              ))}
            </div>
            {intervalError && <p class="sync-interval-error" role="alert">{intervalError}</p>}
          </div>
          {syncing && <span class="sync-bar" aria-hidden="true" />}
        </div>
      )}
      {(error ?? syncError) && (
        <div class="notice error" role="alert">
          {error ?? syncError}
        </div>
      )}

      {items.length > 0 && (
        <div class="mail-tools">
          <div class="mail-tools-group">
            <button
              class="btn"
              type="button"
              disabled={busy}
              onClick={() => setSelected(selected.size === items.length ? new Set() : new Set(items.map((m) => m.id)))}
            >
              <Icon name="check" />
              {selected.size === items.length ? "Clear selection" : "Select all"}
            </button>
            <button class="btn" type="button" disabled={busy || selected.size === 0} onClick={removeSelected}>
              <Icon name="trash" />
              Delete selected{selected.size > 0 ? ` (${selected.size})` : ""}
            </button>
          </div>
          <div class="mail-tools-group">
            <label class="mail-older">
              <span class="muted">Older than</span>
              <input
                class="input"
                type="number"
                min={1}
                inputMode="numeric"
                value={olderDays}
                aria-label="Days"
                onInput={(e) => setOlderDays(Number((e.target as HTMLInputElement).value))}
              />
              <span class="muted">days</span>
            </label>
            <button class="btn" type="button" disabled={busy || !(olderDays >= 1)} onClick={removeOld}>
              <Icon name="trash" />
              Delete old
            </button>
          </div>
        </div>
      )}

      <MailList items={items} showVacancyLink selected={selected} onToggle={toggle} onChanged={(message) => { setToast(message); setReload((n) => n + 1); }} />
      {toast && <Toast message={toast} tone="info" onDismiss={() => setToast(null)} />}
    </section>
  );
}
