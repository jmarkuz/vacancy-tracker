import { useEffect, useRef, useState } from "preact/hooks";
import { ApiError } from "../api/client";
import { listProfiles, type SearchProfile } from "../api/profiles";
import { getRecalculationProgress, startRecalculation, type RecalculationProgress } from "../api/recalculation";
import { progressText, percentDone } from "../domain/recalc";
import { Toast } from "../components/Toast";
import { formatDate } from "./columns";

const POLL_MS = 700;

/**
 * Search profiles (T-053 part): the list and the "Recalculate scores" button with live progress.
 * The rule builder, preview and import arrive with T-055 to T-058.
 */
export function Profiles() {
  const [profiles, setProfiles] = useState<SearchProfile[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [progress, setProgress] = useState<RecalculationProgress | null>(null);
  const [toast, setToast] = useState<{ message: string; tone: "info" | "error" } | null>(null);
  const timer = useRef<number | undefined>(undefined);
  const watching = useRef<string | null>(null);

  useEffect(() => {
    listProfiles()
      .then(setProfiles)
      .catch((e: unknown) => setError(e instanceof ApiError ? e.message : "Could not load the profiles."));
    // Pick up a job that is already running, for example one started by saving a profile.
    getRecalculationProgress()
      .then((p) => {
        if (p) {
          setProgress(p);
          if (p.state === "RUNNING") {
            watch(p.jobId);
          }
        }
      })
      .catch(() => undefined);
    return () => window.clearInterval(timer.current);
  }, []);

  /** Polls until the job we started has finished; then shows the summary once. */
  const watch = (jobId: string) => {
    watching.current = jobId;
    window.clearInterval(timer.current);
    timer.current = window.setInterval(async () => {
      try {
        const p = await getRecalculationProgress();
        if (!p) {
          return;
        }
        setProgress(p);
        // A queued extra pass starts a new job id; keep following until nothing is running.
        if (p.state === "COMPLETED") {
          window.clearInterval(timer.current);
          watching.current = null;
          setToast({ message: progressText(p), tone: p.failed > 0 ? "error" : "info" });
        }
      } catch {
        window.clearInterval(timer.current);
        setToast({ message: "Lost track of the recalculation. It may still be running.", tone: "error" });
      }
    }, POLL_MS);
  };

  const recalculate = async () => {
    setToast(null);
    try {
      const started = await startRecalculation();
      if (!started.started) {
        setToast({ message: "A recalculation is already running. It will run once more when it finishes.", tone: "info" });
      }
      const current = await getRecalculationProgress();
      if (current) {
        setProgress(current);
      }
      watch(started.jobId);
    } catch (e: unknown) {
      setToast({ message: e instanceof Error ? e.message : "Could not start the recalculation.", tone: "error" });
    }
  };

  const running = progress?.state === "RUNNING";
  return (
    <section aria-labelledby="profiles-title">
      <div class="toolbar">
        <h1 id="profiles-title">Search profiles</h1>
        <span class="grow" />
        <button class="btn" type="button" onClick={recalculate} disabled={running} aria-busy={running}>
          {running ? "Recalculating…" : "Recalculate scores"}
        </button>
      </div>

      {progress && (
        <div class="recalc-progress" role="status" aria-live="polite">
          <progress max={100} value={percentDone(progress)} aria-label="Recalculation progress" />
          <span class="muted num">{progressText(progress)}</span>
        </div>
      )}

      {error && (
        <div class="notice error" role="alert">
          {error}
        </div>
      )}

      {profiles && profiles.length === 0 && (
        <div class="panel empty-state">
          <h2>No search profiles yet</h2>
          <p class="muted">Profiles can be edited here once the rule builder is added. Until then they are managed through the API.</p>
        </div>
      )}

      {profiles && profiles.length > 0 && (
        <div class="table-wrap">
          <table aria-label="Search profiles">
            <thead>
              <tr>
                <th scope="col">Name</th>
                <th scope="col">State</th>
                <th scope="col">Sources</th>
                <th scope="col" class="numeric">Rules</th>
                <th scope="col" class="numeric">Version</th>
                <th scope="col">Updated</th>
              </tr>
            </thead>
            <tbody>
              {profiles.map((p) => (
                <tr key={p.id}>
                  <td>
                    <span class="title-text">{p.name}</span>
                  </td>
                  <td>{p.enabled ? "Enabled" : "Disabled"}</td>
                  <td>{p.sources.join(", ")}</td>
                  <td class="numeric num">{p.criteria.length}</td>
                  <td class="numeric num">{p.version}</td>
                  <td class="num">{formatDate(p.updatedAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {toast && <Toast message={toast.message} tone={toast.tone} onDismiss={() => setToast(null)} />}
    </section>
  );
}
