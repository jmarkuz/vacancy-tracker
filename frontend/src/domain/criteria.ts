/**
 * Client side of the criterion model for view filters (ADR-009). The server validates everything;
 * this file only offers the fields and operators it accepts, and turns form input into JSON.
 * Keep FIELDS in sync with CriterionField on the backend.
 */
/** "yes" and "no" are fixed values: the operator carries them and the form asks for nothing. */
export type ValueKind = "text" | "list" | "number" | "date" | "none" | "yes" | "no";

export interface Criterion {
  field: string;
  operator: string;
  value?: unknown;
}

export interface FieldSpec {
  field: string;
  label: string;
  /** key tells apart two choices with the same operator ("is offered" and "is not offered" are both equals). */
  operators: { op: string; label: string; value: ValueKind; key?: string }[];
}

export function operatorKey(o: { op: string; key?: string }): string {
  return o.key ?? o.op;
}

/** Whether the form needs a typed value for this kind. */
export function needsInput(kind: ValueKind): boolean {
  return kind !== "none" && kind !== "yes" && kind !== "no";
}

const TEXT_OPS = [
  { op: "contains_any", label: "contains", value: "list" as const },
  { op: "equals", label: "is", value: "text" as const },
];

export const FIELDS: FieldSpec[] = [
  { field: "title", label: "Title", operators: TEXT_OPS },
  { field: "company", label: "Company", operators: TEXT_OPS },
  { field: "location", label: "Location", operators: TEXT_OPS },
  { field: "keywords", label: "Keywords (title or description)", operators: [{ op: "contains_any", label: "contains", value: "list" }] },
  { field: "workMode", label: "Work mode", operators: [{ op: "equals", label: "is", value: "text" }] },
  {
    field: "status",
    label: "Status",
    operators: [
      { op: "equals", label: "is", value: "text" },
      { op: "in", label: "is one of", value: "list" },
    ],
  },
  {
    field: "priority",
    label: "Priority",
    operators: [
      { op: "equals", label: "is", value: "number" },
      { op: "lte", label: "at most", value: "number" },
    ],
  },
  {
    field: "score",
    label: "Match score",
    operators: [
      { op: "gte", label: "at least", value: "number" },
      { op: "lte", label: "at most", value: "number" },
    ],
  },
  { field: "salary", label: "Salary (yearly)", operators: [{ op: "gte", label: "at least", value: "number" }] },
  { field: "tags", label: "Tags", operators: [{ op: "contains_any", label: "contains", value: "list" }] },
  { field: "source", label: "Source", operators: [{ op: "in", label: "is one of", value: "list" }] },
  {
    field: "postedAt",
    label: "Posted",
    operators: [
      { op: "gte", label: "on or after", value: "date" },
      { op: "lte", label: "on or before", value: "date" },
    ],
  },
  {
    field: "lastSeenAt",
    label: "Last seen",
    operators: [
      { op: "gte", label: "on or after", value: "date" },
      { op: "lte", label: "on or before", value: "date" },
    ],
  },
  {
    field: "nextActionAt",
    label: "Next action",
    operators: [
      { op: "exists", label: "is set", value: "none" },
      { op: "not_exists", label: "is not set", value: "none" },
      { op: "lte", label: "due by", value: "date" },
    ],
  },
  {
    field: "visaSponsorship",
    label: "Visa sponsorship",
    operators: [
      { op: "equals", key: "offered", label: "is offered", value: "yes" },
      { op: "equals", key: "refused", label: "is not offered", value: "no" },
      { op: "not_exists", label: "is not stated", value: "none" },
    ],
  },
];

export function specFor(field: string): FieldSpec | undefined {
  return FIELDS.find((f) => f.field === field);
}

/** Parses the filter URL parameter. Anything malformed is ignored rather than breaking the page. */
export function parseCriteria(raw: string | null): Criterion[] {
  if (!raw) {
    return [];
  }
  try {
    const value: unknown = JSON.parse(raw);
    if (!Array.isArray(value)) {
      return [];
    }
    return value.filter(
      (c): c is Criterion =>
        typeof c === "object" && c !== null && typeof (c as Criterion).field === "string" && typeof (c as Criterion).operator === "string",
    );
  } catch {
    return [];
  }
}

export function serializeCriteria(criteria: Criterion[]): string | null {
  return criteria.length ? JSON.stringify(criteria) : null;
}

/** Turns raw form text into the JSON value the operator expects. Returns undefined when empty. */
export function toValue(kind: ValueKind, text: string): unknown {
  if (kind === "yes" || kind === "no") {
    return kind === "yes";
  }
  const trimmed = text.trim();
  if (kind === "none" || trimmed === "") {
    return undefined;
  }
  if (kind === "list") {
    const items = trimmed.split(",").map((s) => s.trim()).filter(Boolean);
    return items.length ? items : undefined;
  }
  if (kind === "number") {
    const n = Number(trimmed);
    return Number.isFinite(n) ? n : undefined;
  }
  if (kind === "date") {
    const d = new Date(trimmed);
    return Number.isNaN(d.getTime()) ? undefined : d.toISOString();
  }
  return trimmed;
}

export function describe(c: Criterion): string {
  const spec = specFor(c.field);
  const op = spec?.operators.find((o) => o.op === c.operator && (typeof c.value !== "boolean" || o.value === (c.value ? "yes" : "no")));
  const label = spec?.label ?? c.field;
  const opLabel = op?.label ?? c.operator;
  if (c.value === undefined || typeof c.value === "boolean") {
    return `${label} ${opLabel}`;
  }
  const shown = Array.isArray(c.value) ? c.value.join(", ") : String(c.value);
  return `${label} ${opLabel} ${shown}`;
}
