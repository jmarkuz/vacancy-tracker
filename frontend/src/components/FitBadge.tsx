import type { ResumeFit } from "../api/resume";
import { fitSummary, skillsLabel } from "../domain/fit";

/**
 * The fit of a vacancy to the resume as Beacon writes it: "4 of 5 skills" in the purple role (never a percentage),
 * with the reasons in the tooltip and for screen readers.
 */
export function FitBadge({ fit }: { fit: ResumeFit | undefined | null }) {
  if (!fit) {
    return null;
  }
  const summary = fitSummary(fit);
  return (
    <span class="fit-badge" title={summary} aria-label={summary}>
      <span class="num">{skillsLabel(fit)}</span>
    </span>
  );
}
