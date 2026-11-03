import { useEffect, useRef, useState } from "preact/hooks";
import { ApiError } from "../api/client";
import { deleteMail, deleteOldMail, getMailStatus, importEml, listMail, type Mail, type MailStatus, startMailSync } from "../api/mail";
import { MailList } from "../components/MailList";
import { Toast } from "../components/Toast";
import { SYNC_POLL_MS, syncSummary } from "../domain/mail";
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

  return (
    <section aria-labelledby="mail-title" class="stack">
      <div class="toolbar">
        <h1 id="mail-title">Mail</h1>
        <span class="grow" />
        {status && status.pending > 0 && <span class="muted small num">{status.pending} to decide</span>}
        <label class="inline small">
          <input type="checkbox" checked={pendingOnly} onChange={(e) => setPendingOnly((e.target as HTMLInputElement).checked)} /> Only
          suggestions
        </label>
        <button
          class="btn"
          type="button"
          disabled={busy || !status?.configured || status.syncing}
          title={status?.configured ? undefined : "Set up IMAP first (docs/deployment.md)"}
          aria-busy={status?.syncing}
          onClick={() =>
            run(async () => {
              setSyncError(null);
              try {
                await startMailSync();
              } catch (e: unknown) {
                // 409: a read is already running; just follow it.
                if (!(e instanceof ApiError && e.status === 409)) {
                  throw e;
                }
              }
              setStatus(await getMailStatus());
              return "Checking the mailbox…";
            })
          }
        >
          {status?.syncing ? "Checking…" : "Check mail now"}
        </button>
        <label class="btn ghost file-button">
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
      </div>

      {status && !status.configured && (
        <div class="notice">
          The mailbox is not connected. Set the IMAP settings (see docs/deployment.md) to track answers automatically, or import
          single emails as .eml files.
        </div>
      )}
      {status?.configured && (
        <p class="muted small">
          Reading {status.folders.join(", ")} every 15 minutes, read-only. Only job mail is kept.
          {status.lastSync && <> Last check {formatDate(status.lastSync)}.</>}
          {status.lastError && <> Last error: {status.lastError}</>}
        </p>
      )}
      {(error ?? syncError) && (
        <div class="notice error" role="alert">
          {error ?? syncError}
        </div>
      )}

      {items.length > 0 && (
        <div class="toolbar">
          <button
            class="btn ghost"
            type="button"
            disabled={busy}
            onClick={() => setSelected(selected.size === items.length ? new Set() : new Set(items.map((m) => m.id)))}
          >
            {selected.size === items.length ? "Clear selection" : "Select all"}
          </button>
          <button class="btn" type="button" disabled={busy || selected.size === 0} onClick={removeSelected}>
            Delete selected{selected.size > 0 ? ` (${selected.size})` : ""}
          </button>
          <span class="grow" />
          <label class="inline small">
            Older than
            <input
              type="number"
              min={1}
              value={olderDays}
              aria-label="Days"
              style="width: 5rem"
              onInput={(e) => setOlderDays(Number((e.target as HTMLInputElement).value))}
            />
            days
          </label>
          <button class="btn" type="button" disabled={busy || !(olderDays >= 1)} onClick={removeOld}>
            Delete old
          </button>
        </div>
      )}

      <MailList items={items} showVacancyLink selected={selected} onToggle={toggle} onChanged={(message) => { setToast(message); setReload((n) => n + 1); }} />
      {toast && <Toast message={toast} tone="info" onDismiss={() => setToast(null)} />}
    </section>
  );
}
