/** The columns a table shows, from a stored or typed list ("cols" in the address, a saved view's visible columns). */

/** Column that is always shown: it holds the button that opens a vacancy, so a row cannot be left without one. */
export const PINNED_COLUMN = "title";

/**
 * The column ids to show, in the table's own order. Unknown ids (a column removed since the list was saved),
 * duplicates, blanks and stray spaces are dropped; the pinned column is always there; nothing left means the defaults.
 */
export function resolveColumnIds(raw: string | string[] | null | undefined, known: string[], defaults: string[]): string[] {
  const asked = new Set((Array.isArray(raw) ? raw : (raw ?? "").split(",")).map((id) => id.trim()).filter(Boolean));
  const picked = known.filter((id) => asked.has(id));
  if (picked.length === 0) {
    return known.filter((id) => id === PINNED_COLUMN || defaults.includes(id));
  }
  return known.filter((id) => id === PINNED_COLUMN || picked.includes(id));
}

/** Whether two column lists show the same table (so an old saved view with a stale id is not "changed"). */
export function sameColumns(a: string | string[] | null | undefined, b: string | string[] | null | undefined, known: string[], defaults: string[]): boolean {
  return resolveColumnIds(a, known, defaults).join(",") === resolveColumnIds(b, known, defaults).join(",");
}
