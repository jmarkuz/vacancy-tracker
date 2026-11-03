import type { ComponentChildren } from "preact";
import { StatusBadge } from "../components/StatusBadge";
import type { Vacancy } from "../api/types";

export interface Column {
  id: string;
  label: string;
  /** Backend sort key; undefined means the column cannot be sorted. */
  sortKey?: string;
  numeric?: boolean;
  render: (v: Vacancy) => ComponentChildren;
}

const DATE = new Intl.DateTimeFormat(undefined, { dateStyle: "medium", timeStyle: "short" });

export function formatDate(value: string | null): string {
  return value ? DATE.format(new Date(value)) : "-";
}

export function formatSalary(v: Vacancy): string {
  const amount = (n: number) => Math.round(n).toLocaleString();
  if (v.salaryMin == null && v.salaryMax == null) {
    // Unknown is never shown as 0 or a dash (Beacon).
    return "Not specified";
  }
  const range =
    v.salaryMin != null && v.salaryMax != null && v.salaryMin !== v.salaryMax
      ? `${amount(v.salaryMin)}–${amount(v.salaryMax)}`
      : amount((v.salaryMax ?? v.salaryMin) as number);
  const currency = v.currency ? ` ${v.currency}` : "";
  const period = v.salaryPeriod ? ` / ${v.salaryPeriod}` : "";
  return `${range}${currency}${period}`;
}

/** Every column the Pipeline table can show, in default order where it matters. */
export const COLUMNS: Column[] = [
  { id: "title", label: "Title", sortKey: "title", render: (v) => <span class="title-text">{v.title}</span> },
  { id: "company", label: "Company", sortKey: "company", render: (v) => v.company },
  { id: "status", label: "Status", sortKey: "status", render: (v) => <StatusBadge status={v.status} /> },
  { id: "location", label: "Location", sortKey: "location", render: (v) => v.location ?? "-" },
  { id: "workMode", label: "Work mode", sortKey: "workMode", render: (v) => v.workMode ?? "-" },
  {
    id: "salary",
    label: "Salary",
    sortKey: "salaryMax",
    numeric: true,
    render: (v) => <span class="num">{formatSalary(v)}</span>,
  },
  { id: "priority", label: "Priority", sortKey: "priority", numeric: true, render: (v) => v.priority ?? "-" },
  {
    id: "nextActionAt",
    label: "Next action",
    sortKey: "nextActionAt",
    render: (v) => <span class="num">{formatDate(v.nextActionAt)}</span>,
  },
  {
    id: "lastSeenAt",
    label: "Last seen",
    sortKey: "lastSeenAt",
    render: (v) => <span class="num">{formatDate(v.lastSeenAt)}</span>,
  },
  { id: "tags", label: "Tags", render: (v) => (v.tags.length ? v.tags.join(", ") : "-") },
];

export const DEFAULT_COLUMNS = ["title", "company", "status", "location", "salary", "lastSeenAt"];
