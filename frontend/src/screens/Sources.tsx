import { useEffect, useState } from "preact/hooks";
import { ApiError } from "../api/client";
import { listSources, refreshSource, type SourceStatus } from "../api/sources";
import { openRunStream, type RunStream } from "../api/runs";
import { healthDetail, healthLabel } from "../domain/sources";
import { summaryText } from "../domain/refresh";
import { Toast } from "../components/Toast";
import { formatDate } from "./columns";

interface ToastState {
  message: string;
  tone: "info" | "error";
  link?: { href: string; label: string };
}

/** Sources & Runs (T-047): health of every source, the last run per source, and a refresh of one source. */
export function Sources() {
  const [sources, setSources] = useState<SourceStatus[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [toast, setToast] = useState<ToastState | null>(null);
  const [stream, setStream] = useState<RunStream | null>(null);

  const load = () =>
    listSources()
      .then((list) => {
        setSources(list);
        setError(null);
      })
      .catch((e: unknown) => setError(e instanceof ApiError ? e.message : "Could not load sources."));

  useEffect(() => {
    load();
    return () => stream?.close();
  }, []);

  const refresh = async (id: string) => {
    setToast(null);
    setBusy(id);
    try {
      const started = await refreshSource(id);
      if (!started.started) {
        setToast({ message: "A refresh is already running. Wait for it to finish, then try again.", tone: "info" });
      }
      stream?.close();
      setStream(
        openRunStream(started.runId, {
          onSource: () => undefined,
          onDone: (done) => {
            setBusy(null);
            load();
            setToast(
              done.status === "interrupted"
                ? { message: `Refresh of ${id} was interrupted.`, tone: "error" }
                : {
                    message: `${id}: ${summaryText(done)}`,
                    tone: done.failedSources > 0 ? "error" : "info",
                    link: { href: "#/inbox", label: "Open Inbox" },
                  },
            );
          },
          onError: () => {
            setBusy(null);
            setToast({ message: "Lost the connection while refreshing. It may still be running.", tone: "error" });
          },
        }),
      );
    } catch (e: unknown) {
      setBusy(null);
      setToast({ message: e instanceof Error ? e.message : "Could not refresh this source.", tone: "error" });
    }
  };

  return (
    <section aria-labelledby="sources-title">
      <div class="toolbar">
        <h1 id="sources-title">Sources &amp; Runs</h1>
      </div>

      {error && (
        <div class="notice error" role="alert">
          <span>{error}</span>
          <button class="btn" type="button" onClick={load}>
            Retry
          </button>
        </div>
      )}

      {sources && (
        <div class="table-wrap">
          <table aria-label="Sources">
            <thead>
              <tr>
                <th scope="col">Source</th>
                <th scope="col">Health</th>
                <th scope="col">Last success</th>
                <th scope="col" class="numeric">Last run</th>
                <th scope="col"><span class="sr-only">Actions</span></th>
              </tr>
            </thead>
            <tbody>
              {sources.map((s) => (
                <tr key={s.id}>
                  <td>
                    <span class="num">{s.id}</span>
                  </td>
                  <td>
                    <span class={`badge health-${s.health}`}>{healthLabel(s.health)}</span>
                    <div class="muted small">{healthDetail(s)}</div>
                    {s.lastError && <div class="small">Last error: {s.lastError}</div>}
                  </td>
                  <td class="num">{formatDate(s.lastSuccessAt)}</td>
                  <td class="numeric">
                    {s.lastRun ? (
                      <span class="num">
                        +{s.lastRun.newCount} new, {s.lastRun.updated} updated
                        {s.lastRun.errors > 0 ? `, ${s.lastRun.errors} errors` : ""}
                      </span>
                    ) : (
                      "-"
                    )}
                  </td>
                  <td>
                    <button
                      class="btn"
                      type="button"
                      disabled={busy !== null}
                      aria-busy={busy === s.id}
                      onClick={() => refresh(s.id)}
                    >
                      {busy === s.id ? "Refreshing…" : "Refresh this source"}
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {toast && (
        <Toast message={toast.message} tone={toast.tone} link={toast.link} onDismiss={() => setToast(null)} />
      )}
    </section>
  );
}
