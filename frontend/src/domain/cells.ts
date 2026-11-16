import type { Vacancy } from "../api/types";

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

/** Long text for a clipped cell: surrounding blanks go, and nothing left is shown as a dash. */
export function textOrDash(text: string | null | undefined): string {
  return text?.trim() || "-";
}
