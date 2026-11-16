import type { Vacancy } from "../api/types";
import { postedDays } from "./age";

/** Plain-text forms of the facts a Pipeline cell or card shows. Pure, so every odd value can be tested. */

const DATE = new Intl.DateTimeFormat(undefined, { dateStyle: "medium", timeStyle: "short" });

/** A date for a cell. No date, or text that is not a date, is a dash: one bad value must not break the whole table. */
export function formatDate(value: string | null | undefined): string {
  if (!value) {
    return "-";
  }
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? "-" : DATE.format(date);
}

/** An amount that is really pay: zero, negative and not-a-number mean "unknown" (some sources send 0 for "not given"). */
function pay(n: number | null | undefined): number | null {
  return typeof n === "number" && Number.isFinite(n) && n > 0 ? n : null;
}

/** Whole units for big amounts, up to cents for small ones (an hourly 45.50 must not become 46). */
function amount(n: number): string {
  return n.toLocaleString(undefined, { maximumFractionDigits: n < 1000 ? 2 : 0 });
}

const PERIODS: Record<string, string> = {
  hour: "hour", hourly: "hour", hr: "hour",
  day: "day", daily: "day",
  week: "week", weekly: "week",
  month: "month", monthly: "month",
  year: "year", yearly: "year", annual: "year", annually: "year", yr: "year",
};

/** The period of a salary in one spelling; an unknown one is shown as the source gave it, blank is nothing. */
export function periodLabel(period: string | null | undefined): string | null {
  const text = period?.trim().toLowerCase();
  return text ? PERIODS[text] ?? text : null;
}

export function formatSalary(v: Pick<Vacancy, "salaryMin" | "salaryMax" | "currency" | "salaryPeriod">): string {
  let min = pay(v.salaryMin);
  let max = pay(v.salaryMax);
  if (min === null && max === null) {
    // Unknown is never shown as 0 or a dash (Beacon).
    return "Not specified";
  }
  if (min !== null && max !== null && min > max) {
    [min, max] = [max, min];
  }
  let range: string;
  if (min !== null && max !== null) {
    range = min === max ? amount(min) : `${amount(min)}–${amount(max)}`;
  } else if (min !== null) {
    range = `from ${amount(min)}`;
  } else {
    range = `up to ${amount(max as number)}`;
  }
  const currency = v.currency?.trim().toUpperCase();
  const period = periodLabel(v.salaryPeriod);
  return `${range}${currency ? ` ${currency}` : ""}${period ? ` / ${period}` : ""}`;
}

const WORK_MODES: Record<string, string> = { remote: "Remote", hybrid: "Hybrid", onsite: "On-site" };

/**
 * The work mode as the collection stores it ("remote", "hybrid", "onsite"), written for people. Imports and old data
 * may hold other spellings ("On-site", "on site") or other words, which are shown as given.
 */
export function workModeLabel(mode: string | null | undefined): string {
  const text = mode?.trim();
  if (!text) {
    return "-";
  }
  return WORK_MODES[text.toLowerCase().replace(/[\s_-]+/g, "")] ?? text.charAt(0).toUpperCase() + text.slice(1);
}

const PRIORITY_NAMES: Record<number, string> = { 1: "high", 2: "medium", 3: "low" };

/** Priority 1 to 3 with its name (the drawer's words, so "1" is never a guess); other numbers as they are. */
export function priorityLabel(priority: number | null | undefined): string {
  if (typeof priority !== "number" || !Number.isFinite(priority)) {
    return "-";
  }
  const name = PRIORITY_NAMES[priority];
  return name ? `${priority} · ${name}` : String(priority);
}

/** The tags in one line; blank tags are skipped and no tags is a dash. */
export function tagsText(tags: string[] | null | undefined): string {
  const shown = (tags ?? []).map((t) => t.trim()).filter(Boolean);
  return shown.length ? shown.join(", ") : "-";
}

/** The match score as a plain number out of 100; absent (no profile scores the vacancy) is a dash, never 0. */
export function scoreLabel(score: number | null | undefined): string {
  return typeof score === "number" && Number.isFinite(score) ? String(Math.round(score)) : "-";
}

/** What the posting says about visa sponsorship: offered, not offered, or (not stated) a dash. */
export function visaLabel(visa: boolean | null | undefined): string {
  return visa === true ? "Offered" : visa === false ? "Not offered" : "-";
}

/** The sources in one line, "greenhouse" written "Greenhouse"; none (added by hand) is a dash. */
export function sourcesText(sources: string[] | null | undefined): string {
  const names = (sources ?? []).map((s) => s.trim()).filter(Boolean).map((s) => s.charAt(0).toUpperCase() + s.slice(1));
  return names.length ? [...new Set(names)].join(", ") : "-";
}

/** Whole days since the posting date in words ("Today", "1 day", "12 days"); no or unreadable date is a dash. */
export function postedAgeLabel(postedAt: string | null | undefined, now: Date = new Date()): string {
  const days = postedDays(postedAt, now);
  return days === null ? "-" : days === 0 ? "Today" : days === 1 ? "1 day" : `${days} days`;
}

/** Long text for a clipped cell: surrounding blanks go, and nothing left is shown as a dash. */
export function textOrDash(text: string | null | undefined): string {
  return text?.trim() || "-";
}
