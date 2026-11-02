import type { Criterion } from "./criteria";

/**
 * The Inbox filter (T-100): words to look for in the title and description, whether every word or any of them must
 * be there, and words that must not be there. It turns into the same criteria the Pipeline's views use (the
 * "keywords" field), so the server does the filtering over all New vacancies, not only the page on show.
 */
export interface InboxFilter {
  words: string;
  mode: "all" | "any";
  exclude: string;
}

const MAX_WORDS = 20;

export const EMPTY_FILTER: InboxFilter = { words: "", mode: "all", exclude: "" };

/** Words and "quoted phrases", split at spaces and commas, without repeats (ignoring case), at most 20. */
export function parseWords(text: string): string[] {
  const out: string[] = [];
  const seen = new Set<string>();
  for (const m of text.matchAll(/"([^"]+)"|([^\s,;"]+)/g)) {
    const word = (m[1] ?? m[2]).trim().replace(/\s+/g, " ");
    const key = word.toLowerCase();
    if (word && !seen.has(key) && out.length < MAX_WORDS) {
      seen.add(key);
      out.push(word);
    }
  }
  return out;
}

export function inboxCriteria(f: InboxFilter): Criterion[] {
  const criteria: Criterion[] = [];
  const words = parseWords(f.words);
  if (words.length) {
    criteria.push({ field: "keywords", operator: f.mode === "any" ? "contains_any" : "contains_all", value: words });
  }
  const exclude = parseWords(f.exclude);
  if (exclude.length) {
    criteria.push({ field: "keywords", operator: "not_contains", value: exclude });
  }
  return criteria;
}

export function isActive(f: InboxFilter): boolean {
  return inboxCriteria(f).length > 0;
}

/** The filter kept in the address (q, any, not), so a reload or a shared link shows the same list. */
export function filterFromParams(params: URLSearchParams): InboxFilter {
  return { words: params.get("q") ?? "", mode: params.get("any") === "1" ? "any" : "all", exclude: params.get("not") ?? "" };
}

export function filterToParams(f: InboxFilter): Record<string, string | null> {
  return { q: f.words.trim() || null, any: f.mode === "any" ? "1" : null, not: f.exclude.trim() || null };
}
