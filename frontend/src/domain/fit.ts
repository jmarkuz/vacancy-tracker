import type { ResumeFit } from "../api/resume";

/** Score tier for the badge: the colour is never the only signal, the number is always shown. */
export function fitTier(score: number): "high" | "mid" | "low" {
  return score >= 70 ? "high" : score >= 40 ? "mid" : "low";
}

/**
 * The skills the vacancy names and how many of them the resume has (Beacon: "22 of 49 skills", never a percentage);
 * null when the vacancy names no skill the score can compare.
 */
export function skillCount(fit: ResumeFit): { matched: number; total: number } | null {
  const total = fit.matched.length + fit.missing.length;
  return total === 0 ? null : { matched: fit.matched.length, total };
}

/** "4 of 5 skills", or "Not enough data" when the vacancy names no skill to compare. */
export function skillsLabel(fit: ResumeFit): string {
  const count = skillCount(fit);
  return count ? `${count.matched} of ${count.total} skills` : "Not enough data";
}

/** One line for a tooltip or a screen reader: the skills and the first reasons. */
export function fitSummary(fit: ResumeFit): string {
  const count = skillCount(fit);
  const head = count
    ? `${count.matched} of ${count.total} skills the vacancy names are in your resume`
    : "Not enough data: the vacancy names no skill to compare";
  return [head, ...fit.reasons.slice(0, 2).map((r) => r.text)].join(". ");
}

export function fitsById(fits: ResumeFit[]): Record<string, ResumeFit> {
  return Object.fromEntries(fits.map((f) => [f.vacancyId, f]));
}

/** Splits a typed list ("Java, Spring Boot; Kafka") into keywords, dropping blanks and repeats case-insensitively. */
export function parseKeywords(text: string, existing: string[] = []): string[] {
  const seen = new Set(existing.map((k) => k.toLowerCase()));
  const out = [...existing];
  for (const raw of text.split(/[,;\n]/)) {
    const k = raw.trim().replace(/\s+/g, " ");
    if (k && !seen.has(k.toLowerCase())) {
      seen.add(k.toLowerCase());
      out.push(k);
    }
  }
  return out;
}
