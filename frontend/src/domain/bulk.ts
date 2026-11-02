import type { BulkResult, UndoItem } from "../api/bulk";
import type { Vacancy } from "../api/types";
import { allowedNext } from "./statuses";

export interface TargetOption {
  status: string;
  /** How many of the selected vacancies can move there. */
  eligible: number;
  total: number;
}

/** Statuses at least one selected vacancy can move to, most reachable first, with how many can. */
export function targetOptions(selected: Vacancy[]): TargetOption[] {
  const counts = new Map<string, number>();
  for (const v of selected) {
    for (const s of allowedNext(v.status)) {
      counts.set(s, (counts.get(s) ?? 0) + 1);
    }
  }
  return [...counts.entries()]
    .map(([status, eligible]) => ({ status, eligible, total: selected.length }))
    .sort((a, b) => b.eligible - a.eligible || a.status.localeCompare(b.status));
}

export const optionLabel = (o: TargetOption) => (o.eligible === o.total ? o.status : `${o.status} (${o.eligible} of ${o.total})`);

const noun = (n: number) => (n === 1 ? "vacancy" : "vacancies");

/** One sentence for the result of a batch. */
export function bulkSummary(result: BulkResult, target: string): string {
  const moved = result.changed.length;
  const parts = [moved === 0 ? `Nothing moved to ${target}.` : `Moved ${moved} ${noun(moved)} to ${target}.`];
  if (result.skipped.length > 0) {
    parts.push(`${result.skipped.length} skipped: ${[...new Set(result.skipped.map((s) => s.reason))].join("; ")}.`);
  }
  return parts.join(" ");
}

export function undoSummary(result: BulkResult): string {
  const n = result.changed.length;
  const parts = [`Restored ${n} ${noun(n)}.`];
  if (result.skipped.length > 0) {
    parts.push(`${result.skipped.length} changed since and ${result.skipped.length === 1 ? "was" : "were"} left as ${result.skipped.length === 1 ? "it is" : "they are"}.`);
  }
  return parts.join(" ");
}

/** What undo needs to send: each changed vacancy with its old status, from the batch's own result. */
export function undoItems(result: BulkResult, target: string): UndoItem[] {
  return result.changed.map((c) => ({ id: c.id, status: c.from, expected: target, wasNew: c.wasNew }));
}

export function toggle(ids: string[], id: string): string[] {
  return ids.includes(id) ? ids.filter((x) => x !== id) : [...ids, id];
}

/** Selects every visible row, or clears them when all are already selected. */
export function togglePage(selected: string[], visible: string[]): string[] {
  const all = visible.length > 0 && visible.every((id) => selected.includes(id));
  return all ? selected.filter((id) => !visible.includes(id)) : [...new Set([...selected, ...visible])];
}
