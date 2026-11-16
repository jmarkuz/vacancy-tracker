import type { ComponentChildren } from "preact";
import { StatusBadge } from "../components/StatusBadge";
import type { Vacancy } from "../api/types";
import { GhostBadge } from "../components/GhostBadge";
import {
  formatDate, formatSalary, postedAgeLabel, priorityLabel, scoreLabel, sourcesText, tagsText, textOrDash, visaLabel,
  workModeLabel,
} from "../domain/cells";

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

/**
 * Every column the Pipeline table can show, in the order they appear. DEFAULT_COLUMNS are on until the user picks;
 * the others are optional and offered in the column picker.
 */
export const COLUMNS: Column[] = [
  { id: "title", label: "Title", sortKey: "title", render: (v) => <span class="title-text">{v.title?.trim() || "(untitled)"}</span> },
  { id: "company", label: "Company", sortKey: "company", render: (v) => clip(v.company) },
  { id: "status", label: "Status", sortKey: "status", render: (v) => <StatusBadge status={v.status} /> },
  { id: "location", label: "Location", sortKey: "location", render: (v) => clip(v.location) },
  { id: "country", label: "Country", sortKey: "country", render: (v) => clip(v.country?.trim().toUpperCase()) },
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
    id: "matchScore",
    label: "Match score",
    sortKey: "matchScore",
    numeric: true,
    render: (v) => <span class="num" title="The best score any profile gives this vacancy, out of 100">{scoreLabel(v.matchScore)}</span>,
  },
  { id: "visa", label: "Visa", sortKey: "visaSponsorship", render: (v) => visaLabel(v.visaSponsorship) },
  {
    id: "ghost",
    label: "Ghost check",
    render: (v) => (v.ghost?.likely ? <GhostBadge ghost={v.ghost} /> : "-"),
  },
  {
    id: "nextActionAt",
    label: "Next action",
    sortKey: "nextActionAt",
    render: (v) => <span class="num">{formatDate(v.nextActionAt)}</span>,
  },
  {
    id: "firstSeenAt",
    label: "First seen",
    sortKey: "firstSeenAt",
    render: (v) => <span class="num">{formatDate(v.firstSeenAt)}</span>,
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
  {
    id: "postedAge",
    label: "Posted age",
    render: (v) => <span class="num">{postedAgeLabel(v.postedAt)}</span>,
  },
  { id: "sources", label: "Sources", render: (v) => clip(sourcesText(v.sources)) },
  { id: "tags", label: "Tags", render: (v) => clip(tagsText(v.tags)) },
];

export const DEFAULT_COLUMNS = ["title", "company", "status", "location", "salary", "lastSeenAt"];
