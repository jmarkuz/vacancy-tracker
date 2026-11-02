import { useEffect, useState } from "preact/hooks";
import { ApiError } from "../api/client";
import { getMailStatus, importEml, listMail, type Mail, type MailStatus, syncMail } from "../api/mail";
import { MailList } from "../components/MailList";
import { Toast } from "../components/Toast";
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
  const [toast, setToast] = useState<string | null>(null);
  const [reload, setReload] = useState(0);

  useEffect(() => {
    const controller = new AbortController();
    Promise.all([listMail({ pending: pendingOnly }, controller.signal), getMailStatus(controller.signal)])
      .then(([list, s]) => {
        setItems(list);
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
          disabled={busy || !status?.configured}
          title={status?.configured ? undefined : "Set up IMAP first (docs/deployment.md)"}
          onClick={() =>
            run(async () => {
              const r = await syncMail();
              if (r.error) {
                throw new ApiError(502, r.error);
              }
              return `Read ${r.seen} mails: ${r.stored} about jobs, ${r.linked} linked, ${r.suggested} suggestions.`;
            })
          }
        >
          Check mail now
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
      {error && (
        <div class="notice error" role="alert">
          {error}
        </div>
      )}

      <MailList items={items} showVacancyLink onChanged={(message) => { setToast(message); setReload((n) => n + 1); }} />
      {toast && <Toast message={toast} tone="info" onDismiss={() => setToast(null)} />}
    </section>
  );
}
