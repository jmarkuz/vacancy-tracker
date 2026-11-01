import type { Vacancy } from "../api/types";
import { allowedNext } from "./statuses";

/** The board's columns, left to right. New, Hidden, Dismissed and Expired belong to the Inbox, not the board. */
export const BOARD_COLUMNS = ["Saved", "Applied", "Screening", "Interview", "Test task", "Offer", "Accepted", "Rejected", "Withdrawn"];

export function canMove(from: string, to: string): boolean {
  return allowedNext(from).includes(to);
}

/** Vacancies per column in the given order; statuses without a column are dropped. */
export function groupByStatus(items: Vacancy[]): Record<string, Vacancy[]> {
  const out: Record<string, Vacancy[]> = Object.fromEntries(BOARD_COLUMNS.map((c) => [c, []]));
  for (const v of items) {
    out[v.status]?.push(v);
  }
  return out;
}

/** The column one step to the left (-1) or right (+1) of a status, or null at the edge or off the board. */
export function neighbourColumn(status: string, delta: -1 | 1): string | null {
  const i = BOARD_COLUMNS.indexOf(status);
  const next = i < 0 ? -1 : i + delta;
  return next >= 0 && next < BOARD_COLUMNS.length ? BOARD_COLUMNS[next] : null;
}

/** What to tell the user after a keyboard move, or why it was refused. Null target means the edge. */
export function moveMessage(title: string, from: string, target: string | null): { ok: boolean; text: string } {
  if (target === null) {
    return { ok: false, text: `"${title}" is already in the ${from === BOARD_COLUMNS[0] ? "first" : "last"} column.` };
  }
  if (!canMove(from, target)) {
    return { ok: false, text: `"${title}" cannot move from ${from} to ${target}.` };
  }
  return { ok: true, text: `Moved "${title}" to ${target}.` };
}

/** Replaces one vacancy's status, leaving the rest as they are. */
export function withStatus(items: Vacancy[], id: string, status: string): Vacancy[] {
  return items.map((v) => (v.id === id ? { ...v, status } : v));
}
