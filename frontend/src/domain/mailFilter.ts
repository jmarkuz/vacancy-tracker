/** The filters of the Mail screen (kept in the URL, applied by the server). */

/** The kinds of mail, in the order the chips show them. The ids are the server's `category` values. */
export const MAIL_CATEGORIES = [
  { id: "job_alert", label: "Job alerts" },
  { id: "interview", label: "Interviews" },
  { id: "offer", label: "Offers" },
  { id: "rejection", label: "Rejections" },
  { id: "test_task", label: "Test tasks" },
  { id: "recruiter_message", label: "Recruiter messages" },
  { id: "other", label: "Other" },
] as const;

export type LinkedFilter = "all" | "linked" | "unlinked";

export interface MailFilters {
  /** Chosen kinds; empty means all. */
  categories: string[];
  linked: LinkedFilter;
  /** The search text as typed ("" = none). */
  q: string;
}

export const NO_MAIL_FILTERS: MailFilters = { categories: [], linked: "all", q: "" };

const KNOWN = new Set<string>(MAIL_CATEGORIES.map((c) => c.id));

/** Reads the filters from the URL query (`cat=offer,rejection&linked=false&q=acme`); unknown values are ignored. */
export function parseMailFilters(params: URLSearchParams): MailFilters {
  const wanted = new Set((params.get("cat") ?? "").split(",").map((s) => s.trim()));
  const linked = params.get("linked");
  return {
    // Chips keep their fixed order, whatever order the URL had.
    categories: MAIL_CATEGORIES.map((c) => c.id).filter((id) => wanted.has(id)),
    linked: linked === "true" ? "linked" : linked === "false" ? "unlinked" : "all",
    q: params.get("q") ?? "",
  };
}

/** The URL query changes for these filters (null removes a parameter), for `setQuery`. */
export function mailFilterQuery(f: MailFilters): Record<"cat" | "linked" | "q", string | null> {
  return {
    cat: f.categories.length > 0 ? f.categories.join(",") : null,
    linked: f.linked === "linked" ? "true" : f.linked === "unlinked" ? "false" : null,
    q: f.q.trim() === "" ? null : f.q,
  };
}

export const hasMailFilters = (f: MailFilters): boolean => f.categories.length > 0 || f.linked !== "all" || f.q.trim() !== "";

/** Adds the category to the choice, or removes it when it is already chosen. */
export function toggleCategory(categories: string[], id: string): string[] {
  if (!KNOWN.has(id)) {
    return categories;
  }
  return MAIL_CATEGORIES.map((c) => c.id).filter((c) => (c === id ? !categories.includes(c) : categories.includes(c)));
}

/** The server's `linked` parameter for this choice; undefined for All. */
export const linkedParam = (linked: LinkedFilter): boolean | undefined => (linked === "all" ? undefined : linked === "linked");
