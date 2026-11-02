import type { ResumeFit } from "../api/resume";
import { fitSummary, fitTier } from "../domain/fit";

/** The fit of a vacancy to the resume: the number, a tier colour and the reasons in the tooltip and for screen readers. */
export function FitBadge({ fit }: { fit: ResumeFit | undefined | null }) {
  if (!fit) {
    return null;
  }
  const summary = fitSummary(fit);
  return (
    <span class={`fit-badge fit-${fitTier(fit.score)}`} title={summary} aria-label={summary}>
      <span aria-hidden="true">Fit</span> <span class="num">{fit.score}</span>
    </span>
  );
}
