import type { FunnelStage, Week } from "../api/dashboard";
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
