import type { ComponentChildren } from "preact";
import { StatusBadge } from "../components/StatusBadge";
import type { Vacancy } from "../api/types";
import { formatDate, formatSalary, priorityLabel, tagsText, textOrDash, workModeLabel } from "../domain/cells";

export interface Column {
  id: string;
  label: string;
  /** Backend sort key; undefined means the column cannot be sorted. */
  sortKey?: string;
  numeric?: boolean;
  render: (v: Vacancy) => ComponentChildren;
}

export { formatDate, formatSalary };

/** Long text stays on one line and ends in an ellipsis; the full text is in the tooltip. */
export function clip(text: string | null | undefined): ComponentChildren {
  const value = textOrDash(text);
  return <span class="cell-clip" title={value}>{value}</span>;
}

/** Every column the Pipeline table can show, in default order where it matters. */
export const COLUMNS: Column[] = [
  { id: "title", label: "Title", sortKey: "title", render: (v) => <span class="title-text">{v.title}</span> },
  { id: "company", label: "Company", sortKey: "company", render: (v) => clip(v.company) },
  { id: "status", label: "Status", sortKey: "status", render: (v) => <StatusBadge status={v.status} /> },
  { id: "location", label: "Location", sortKey: "location", render: (v) => clip(v.location) },
  { id: "workMode", label: "Work mode", sortKey: "workMode", render: (v) => workModeLabel(v.workMode) },
  {
    id: "salary",
    label: "Salary",
    sortKey: "salaryMax",
    numeric: true,
    render: (v) => <span class="num">{formatSalary(v)}</span>,
  },
  { id: "priority", label: "Priority", sortKey: "priority", render: (v) => priorityLabel(v.priority) },
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
  {
    id: "postedAt",
    label: "Posted",
    sortKey: "postedAt",
    render: (v) => <span class="num">{formatDate(v.postedAt ?? null)}</span>,
  },
  { id: "tags", label: "Tags", render: (v) => clip(tagsText(v.tags)) },
];

export const DEFAULT_COLUMNS = ["title", "company", "status", "location", "salary", "lastSeenAt"];
