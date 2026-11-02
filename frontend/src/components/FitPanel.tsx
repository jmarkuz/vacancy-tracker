import { useEffect, useState } from "preact/hooks";
import { getFit, type ResumeFit } from "../api/resume";
import { FitBadge } from "./FitBadge";

/** The fit of one vacancy to the resume, with every reason (T-081). Shown in the vacancy drawer. */
export function FitPanel({ vacancyId }: { vacancyId: string }) {
  const [fit, setFit] = useState<ResumeFit | null | undefined>(undefined);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    const controller = new AbortController();
    setFit(undefined);
    setFailed(false);
    getFit(vacancyId, controller.signal)
      .then(setFit)
      .catch(() => {
        if (!controller.signal.aborted) {
          setFailed(true);
        }
      });
    return () => controller.abort();
  }, [vacancyId]);

  return (
    <section class="drawer-section" aria-labelledby="fit-title">
      <h3 id="fit-title">
        Fit to your resume <FitBadge fit={fit} />
      </h3>
      {failed && <p class="muted">The fit could not be loaded.</p>}
      {fit === undefined && !failed && <p class="muted">Loading…</p>}
      {fit === null && (
        <p class="muted">
          <a href="#/resume">Upload your resume</a> to see how this vacancy fits it.
        </p>
      )}
      {fit && (
        <ul class="reasons">
          {fit.reasons.map((r, i) => (
            <li key={i} class={`reason reason-${r.kind}`}>
              {r.text}
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
