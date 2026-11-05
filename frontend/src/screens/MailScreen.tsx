import { useEffect, useMemo, useRef, useState } from "preact/hooks";
import { ApiError } from "../api/client";
import {
  deleteMail,
  deleteOldMail,
  getMailStatus,
  importEml,
  listMail,
  type Mail,
  type MailStatus,
  startMailReread,
  startMailSync,
} from "../api/mail";
import { ConfirmDialog } from "../components/ConfirmDialog";
import { Icon } from "../components/Icon";
import { MailList } from "../components/MailList";
import { Toast } from "../components/Toast";
import { DELETE_UNDO_MS, deleteConfirmText, deletingText, olderThan, REREAD_CONFIRM, SYNC_POLL_MS, syncSummary } from "../domain/mail";
import { formatDate } from "./columns";

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
  // A delete the owner is asked about (`days` is set for "Delete old"), and the mails of a confirmed delete that wait
  // for their Undo: they are hidden from the list at once and come back on Undo or on failure.
  const [confirming, setConfirming] = useState<{ ids: string[]; days: number | null } | null>(null);
  const [hidden, setHidden] = useState<ReadonlySet<string>>(new Set());
  const [undoToast, setUndoToast] = useState<{ message: string; undo: () => void } | null>(null);
  const pending = useRef<{ timer: ReturnType<typeof setTimeout>; send: () => Promise<void> } | null>(null);
  const mounted = useRef(true);
  // Deleted mails stay hidden until the next list no longer has them, so they do not flash back.
  const released = useRef<string[]>([]);

  useEffect(() => {
    const controller = new AbortController();
    Promise.all([listMail({ pending: pendingOnly }, controller.signal), getMailStatus(controller.signal)])
      .then(([list, s]) => {
        setItems(list);
        if (released.current.length > 0) {
          const done = released.current;
          released.current = [];
          setHidden((prev) => new Set([...prev].filter((id) => !done.includes(id))));
        }
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

  /** Sends the waiting delete now (Undo is no longer possible). */
  const flush = () => {
    const entry = pending.current;
    if (entry) {
      clearTimeout(entry.timer);
      pending.current = null;
      void entry.send();
    }
  };

  // Leaving the screen must not lose a delete the owner confirmed.
  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
      flush();
    };
  }, []);

  const askDelete = (ids: string[], days: number | null) => {
    setConfirming({ ids, days });
  };

  const removeSelected = () => {
    if (selected.size > 0) {
      askDelete([...selected], null);
    }
  };

  const removeOld = () => {
    const days = Math.floor(olderDays);
    if (days >= 1) {
      askDelete(olderThan(items, days, Date.now()), days);
    }
  };

  /** The owner confirmed: hide the mails, show Undo, and send the delete after the delay. */
  const startDelete = ({ ids, days }: { ids: string[]; days: number | null }) => {
    setConfirming(null);
    flush();
    const gone = new Set(ids);
    setHidden((prev) => new Set([...prev, ...gone]));
    setSelected((prev) => new Set([...prev].filter((id) => !gone.has(id))));
    const show = (ids2: string[]) => setHidden((prev) => new Set([...prev].filter((id) => !ids2.includes(id))));
    const send = async () => {
      if (mounted.current) {
        setUndoToast(null);
      }
      try {
        const deleted = (days === null ? await deleteMail(ids) : await deleteOldMail(days)).deleted;
        if (mounted.current) {
          setToast(days === null ? `Deleted ${deleted} mails.` : `Deleted ${deleted} mails older than ${days} days.`);
          released.current = [...released.current, ...ids];
          setReload((n) => n + 1);
        }
      } catch (e: unknown) {
        if (mounted.current) {
          show(ids);
          setError(e instanceof ApiError ? e.message : "That did not work.");
        }
      }
    };
    pending.current = { timer: setTimeout(() => flush(), DELETE_UNDO_MS), send };
    setToast(null);
    setUndoToast({
      message: deletingText(ids.length, days),
      undo: () => {
        if (pending.current) {
          clearTimeout(pending.current.timer);
          pending.current = null;
        }
        show(ids);
        setSelected((prev) => new Set([...prev, ...ids]));
        setUndoToast(null);
      },
    });
  };

  const visible = useMemo(() => items.filter((m) => !hidden.has(m.id)), [items, hidden]);
  const confirmText = confirming ? deleteConfirmText(confirming.ids.length, confirming.days) : null;

  const syncing = !!status?.syncing;
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
        <div class={`sync-card${syncing ? " is-syncing" : ""}`} role="status" aria-live="polite">
          <span class="sync-icon" aria-hidden="true">
            <Icon name={syncing ? "refresh" : "mail"} spin={syncing} />
          </span>
          <div class="sync-text">
            <strong>{syncing ? "Checking the mailbox…" : "Mailbox connected"}</strong>
            <span class="muted">
              Reading {status.folders.join(", ")} every 15 minutes, read-only. Only job mail is kept.
              {status.lastSync && <> Last check {formatDate(status.lastSync)}.</>}
              {status.lastError && <> Last error: {status.lastError}</>}
            </span>
          </div>
          {syncing && <span class="sync-bar" aria-hidden="true" />}
        </div>
      )}
      {(error ?? syncError) && (
        <div class="notice error" role="alert">
          {error ?? syncError}
        </div>
      )}

      {visible.length > 0 && (
        <div class="mail-tools">
          <div class="mail-tools-group">
            <button
              class="btn"
              type="button"
              disabled={busy}
              onClick={() => setSelected(selected.size === visible.length ? new Set() : new Set(visible.map((m) => m.id)))}
            >
              <Icon name="check" />
              {selected.size === visible.length ? "Clear selection" : "Select all"}
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

      <MailList items={visible} showVacancyLink selected={selected} onToggle={toggle} onChanged={(message) => { setToast(message); setReload((n) => n + 1); }} />
      {confirming && confirmText && (
        <ConfirmDialog
          title={confirmText.title}
          message={confirmText.body}
          confirmLabel={confirmText.action}
          onConfirm={() => startDelete(confirming)}
          onCancel={() => setConfirming(null)}
        />
      )}
      {undoToast ? (
        <Toast message={undoToast.message} tone="info" action={{ label: "Undo", onClick: undoToast.undo }} onDismiss={() => setUndoToast(null)} />
      ) : (
        toast && <Toast message={toast} tone="info" onDismiss={() => setToast(null)} />
      )}
    </section>
  );
}
