import { useEffect, useRef, useState } from "preact/hooks";
import { type LatestRun, type RunStream, type SourceEvent, getLatestRun, openRunStream, startRun } from "../api/runs";
import { refreshedLine, summaryText } from "../domain/refresh";
import { Toast } from "./Toast";

type Phase = "idle" | "running";

interface ToastState {
  message: string;
  tone: "info" | "error";
  link?: { href: string; label: string };
}

/**
 * Header control: starts a refresh, shows per-source progress while it runs (T-046), and then a summary
 * toast that links to the Inbox. The "Last refreshed" line is read from the server.
 */
export function RefreshControl() {
  const [phase, setPhase] = useState<Phase>("idle");
  const [progress, setProgress] = useState<SourceEvent[]>([]);
  const [latest, setLatest] = useState<LatestRun | null>(null);
  const [toast, setToast] = useState<ToastState | null>(null);
  const stream = useRef<RunStream | null>(null);

  const loadLatest = () => {
    getLatestRun()
      .then(setLatest)
      .catch(() => setLatest(null));
  };

  useEffect(() => {
    loadLatest();
    return () => stream.current?.close();
  }, []);

  const refresh = async () => {
    setToast(null);
    setProgress([]);
    setPhase("running");
    try {
      const started = await startRun();
      stream.current?.close();
      stream.current = openRunStream(started.runId, {
        onSource: (event) =>
          setProgress((items) => {
            const others = items.filter((i) => i.adapter !== event.adapter);
            return [...others, event];
          }),
        onDone: (done) => {
          setPhase("idle");
          loadLatest();
          if (done.status === "interrupted") {
            setToast({ message: "The refresh was interrupted before it finished.", tone: "error" });
            return;
          }
          setToast({
            message: summaryText(done),
            tone: done.failedSources > 0 ? "error" : "info",
            link: { href: "#/inbox", label: "Open Inbox" },
          });
        },
        onError: () => {
          setPhase("idle");
          setToast({ message: "Lost the connection to the refresh. It may still be running.", tone: "error" });
        },
      });
    } catch (e: unknown) {
      setPhase("idle");
      setToast({ message: e instanceof Error ? e.message : "Could not start a refresh.", tone: "error" });
    }
  };

  const running = phase === "running";
  return (
    <div class="refresh">
      <span class="refresh-meta num" aria-live="polite">
        {latest ? refreshedLine(latest.finishedAt, latest.nextScheduledAt) : ""}
      </span>
      <button class="btn primary" type="button" onClick={refresh} disabled={running} aria-busy={running}>
        {running ? "Refreshing…" : "Refresh"}
      </button>

      {running && progress.length > 0 && (
        <ul class="progress-list" aria-label="Refresh progress">
          {progress.map((p) => (
            <li key={p.adapter}>
              <span class="num">{p.adapter}</span>{" "}
              <span class={`badge ${p.state === "failed" ? "health-error" : p.state === "running" ? "" : "health-ok"}`}>
                {p.state === "running" ? "running" : p.state === "failed" ? "failed" : "ok"}
              </span>
              {p.state === "finished" && <span class="muted"> · +{p.new ?? 0} new</span>}
              {p.state === "failed" && p.error && <span class="muted"> · {p.error}</span>}
            </li>
          ))}
        </ul>
      )}

      {toast && (
        <Toast message={toast.message} tone={toast.tone} link={toast.link} onDismiss={() => setToast(null)} />
      )}
    </div>
  );
}
