import type { FunnelStage, ReplyOutcomes, Week } from "../api/dashboard";
import { serializeCriteria } from "./criteria";

/** The statuses of a vacancy the user is working on. Keep in sync with DashboardService.ACTIVE. */
export const ACTIVE_STATUSES = ["Saved", "Applied", "Screening", "Interview", "Test task", "Offer"];

export interface Kpi {
  id: string;
  label: string;
  value: number;
  /** Where the card leads: a hash route with its filter. */
  href: string;
}

const pipeline = (criteria: { field: string; operator: string; value?: unknown }[]) =>
  `#/pipeline?filter=${encodeURIComponent(serializeCriteria(criteria) ?? "")}`;

export function kpis(d: { newToday: number; inbox: number; active: number; interviews: number; overdue: number }, now: Date): Kpi[] {
  const active = { field: "status", operator: "in", value: ACTIVE_STATUSES };
  return [
    { id: "new-today", label: "New today", value: d.newToday, href: "#/inbox" },
    { id: "inbox", label: "Inbox", value: d.inbox, href: "#/inbox" },
    { id: "active", label: "Active", value: d.active, href: pipeline([active]) },
    { id: "interviews", label: "In interviews", value: d.interviews, href: pipeline([{ field: "status", operator: "equals", value: "Interview" }]) },
    { id: "overdue", label: "Overdue", value: d.overdue, href: pipeline([active, { field: "nextActionAt", operator: "lte", value: now.toISOString() }]) },
  ];
}

/** Bar width in percent relative to the largest value; a nonzero count is never thinner than 2 percent. */
export function barPercent(value: number, max: number): number {
  if (max <= 0 || value <= 0) {
    return 0;
  }
  return Math.max(2, Math.round((value / max) * 100));
}

export const funnelMax = (stages: FunnelStage[]) => Math.max(0, ...stages.map((s) => s.count));
export const weekMax = (weeks: Week[]) => Math.max(0, ...weeks.map((w) => w.applications));

/** "Nov 3" for a week start given as yyyy-mm-dd. Built from the parts so no time zone shifts the day. */
export function weekLabel(start: string): string {
  const [y, m, d] = start.split("-").map(Number);
  return new Date(y, m - 1, d).toLocaleDateString("en-US", { month: "short", day: "numeric" });
}

export function chartSummary(weeks: Week[]): string {
  return "Applications per week: " + weeks.map((w) => `${weekLabel(w.start)} ${w.applications}`).join(", ");
}

/** "Quiet for 12 days", "Quiet for 1 day", or "Quiet for over a month" from 30 days on. */
export function quietText(days: number): string {
  if (days >= 30) {
    return "Quiet for over a month";
  }
  return `Quiet for ${days} ${days === 1 ? "day" : "days"}`;
}

/** Reply rate as a whole percent, 0 when nothing was applied (never NaN); a nonzero rate is never shown as 0. */
export function repliedPercent(applied: number, replied: number): number {
  if (applied <= 0 || replied <= 0) {
    return 0;
  }
  return Math.min(100, Math.max(1, Math.round((replied / applied) * 100)));
}

/** "Same day", "1 day", "2.5 days" or "12 days" for the median wait; "No replies yet" when there is none. */
export function medianText(days: number | null): string {
  if (days === null || !Number.isFinite(days)) {
    return "No replies yet";
  }
  if (days < 0.5) {
    return "Same day";
  }
  const rounded = days < 10 ? Math.round(days * 2) / 2 : Math.round(days);
  return `${rounded.toLocaleString("en-US")} ${rounded === 1 ? "day" : "days"}`;
}

export interface OutcomeRow {
  id: keyof ReplyOutcomes;
  label: string;
  /** The mail category class that gives the colour: the same roles as in the Mail screen. */
  className: string;
  count: number;
}

/** The outcomes in the order of importance, with the words used by the Mail screen's category labels. */
export function outcomeRows(o: ReplyOutcomes): OutcomeRow[] {
  return [
    { id: "interview", label: "Interview", className: "cat-interview", count: o.interview },
    { id: "testTask", label: "Test task", className: "cat-test_task", count: o.testTask },
    { id: "offer", label: "Offer", className: "cat-offer", count: o.offer },
    { id: "rejection", label: "Rejection", className: "cat-rejection", count: o.rejection },
    { id: "other", label: "Other", className: "", count: o.other },
  ];
}

/** The period in words: "the last 30 days". */
export const periodText = (days: number) => `the last ${days} days`;
