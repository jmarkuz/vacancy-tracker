import type { RecalculationProgress } from "../api/recalculation";

/** Whole percent done, 0 to 100. An empty job counts as done. */
export function percentDone(p: Pick<RecalculationProgress, "processed" | "total" | "state">): number {
  if (p.total === 0) {
    return p.state === "COMPLETED" ? 100 : 0;
  }
  return Math.min(100, Math.floor((p.processed / p.total) * 100));
}

/** One line for the progress area and the final toast. */
export function progressText(p: RecalculationProgress): string {
  if (p.state === "RUNNING" && p.total === 0) {
    return "Recalculating: counting vacancies…";
  }
  if (p.state === "RUNNING") {
    return `Recalculating: ${p.processed.toLocaleString()} of ${p.total.toLocaleString()} (${percentDone(p)}%)`;
  }
  const base = `Scores recalculated for ${p.processed.toLocaleString()} ${p.processed === 1 ? "vacancy" : "vacancies"}`;
  return p.failed > 0 ? `${base}; ${p.failed} could not be scored` : base;
}
