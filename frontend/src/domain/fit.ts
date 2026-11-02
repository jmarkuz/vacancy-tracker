import type { ResumeFit } from "../api/resume";

/** Score tier for the badge: the colour is never the only signal, the number is always shown. */
export function fitTier(score: number): "high" | "mid" | "low" {
  return score >= 70 ? "high" : score >= 40 ? "mid" : "low";
}

/** One line for a tooltip or a screen reader: the score and the first reasons. */
export function fitSummary(fit: ResumeFit): string {
  return [`Fit ${fit.score} of 100`, ...fit.reasons.slice(0, 2).map((r) => r.text)].join(". ");
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
