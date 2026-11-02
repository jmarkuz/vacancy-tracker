import type { EmployerMatch } from "../api/analysis";

/** Base draft versus a tailored text on the same checklist (T-096). */
export interface Comparison {
  base: number;
  tailored: number;
  delta: number;
  /** Requirements the tailored text covers that the base draft did not cover fully. */
  newlyCovered: string[];
  /** Requirements still missing in the tailored text. */
  stillMissing: string[];
}

export function compare(base: EmployerMatch, tailored: EmployerMatch): Comparison {
  const before = new Map(base.items.map((i) => [i.requirementId, i.status]));
  return {
    base: base.score,
    tailored: tailored.score,
    delta: tailored.score - base.score,
    newlyCovered: tailored.items.filter((i) => i.status === "covered" && before.get(i.requirementId) !== "covered").map((i) => i.text),
    stillMissing: tailored.items.filter((i) => i.status === "missing").map((i) => i.text),
  };
}

/** "Base 62% → Tailored 78% (+16)"; the label names the second text. */
export function comparisonLine(c: Comparison, label = "Tailored"): string {
  const sign = c.delta > 0 ? "+" : c.delta < 0 ? "−" : "±";
  return `Base ${c.base}% → ${label} ${c.tailored}% (${sign}${Math.abs(c.delta)})`;
}

/** A word for the level, so the bar does not rely on colour. */
export function level(score: number): "low" | "medium" | "high" {
  return score >= 70 ? "high" : score >= 40 ? "medium" : "low";
}
