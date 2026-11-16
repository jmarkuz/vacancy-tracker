/** Sort keys the backend accepts for the vacancy list (VacancySort). A key outside this list is a 422, so it is never sent. */
export const SORT_KEYS = [
  "title", "company", "location", "country", "workMode", "status", "priority", "salaryMax", "matchScore",
  "visaSponsorship", "firstSeenAt", "lastSeenAt", "postedAt", "nextActionAt",
] as const;

export const DEFAULT_SORT = "lastSeenAt:desc";

export interface ParsedSort {
  key: string;
  direction: "asc" | "desc";
  /** The canonical "key:direction" to send and to compare with. */
  value: string;
}

/**
 * The sort of the address, made safe: "key" alone means ascending (as on the server), the direction may be in any
 * case, and anything the server would reject (an old link, a hand-edited address) becomes the default order.
 */
export function parseSort(raw: string | null | undefined): ParsedSort {
  const [key, dir, ...rest] = (raw ?? "").split(":");
  const direction = dir === undefined ? "asc" : dir.trim().toLowerCase();
  if (rest.length === 0 && (SORT_KEYS as readonly string[]).includes(key?.trim()) && (direction === "asc" || direction === "desc")) {
    return { key: key.trim(), direction, value: `${key.trim()}:${direction}` };
  }
  const [defaultKey, defaultDirection] = DEFAULT_SORT.split(":");
  return { key: defaultKey, direction: defaultDirection as "desc", value: DEFAULT_SORT };
}
