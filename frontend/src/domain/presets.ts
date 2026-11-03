/** Pure helpers of the Presets screen (T-093): placeholders still to fill and the actual share of each weight. */

const MARK = /\{\{([^{}\n]{1,200})\}\}/g;

/** The distinct {{placeholder}} marks of a text, in order; the same rule as the server's Placeholders. */
export function placeholders(text: string): string[] {
  const found = new Set<string>();
  for (const m of text.matchAll(MARK)) {
    found.add(m[1].trim());
  }
  return [...found];
}

/** Each weight's percentage of the total, to one decimal; all zero when the total is zero. */
export function shares(weights: { weight: number }[]): number[] {
  const total = weights.reduce((sum, w) => sum + (Number.isFinite(w.weight) && w.weight > 0 ? w.weight : 0), 0);
  return weights.map((w) => (total === 0 || !(w.weight > 0) ? 0 : Math.round((w.weight / total) * 1000) / 10));
}

/** Why the weights cannot be saved, or null when they can. */
export function weightsProblem(weights: { name: string; weight: number }[]): string | null {
  if (weights.length === 0) {
    return "Add at least one parameter.";
  }
  const names = new Set<string>();
  for (const w of weights) {
    const name = w.name.trim().toLowerCase();
    if (!name) {
      return "Every parameter needs a name.";
    }
    if (names.has(name)) {
      return `"${w.name.trim()}" is listed twice.`;
    }
    names.add(name);
    if (!Number.isInteger(w.weight) || w.weight < 0 || w.weight > 1000) {
      return "Weights are whole numbers from 0 to 1000.";
    }
  }
  return weights.some((w) => w.weight > 0) ? null : "Give at least one parameter a weight above zero.";
}
