/**
 * Pure editing rules for the profile rule builder (T-055): reorder, duplicate, values by operator shape,
 * and mapping server errors to rows. The server stays the judge of what is valid; these functions only
 * keep the draft well formed while it is being edited.
 */
import type { Criterion, Meta, ProfileInput, SearchProfile, ValueShape } from "../api/profiles";

/** A value to start from when an operator needs a given shape of value. Invalid on purpose until filled in. */
export function defaultValue(shape: ValueShape): unknown {
  switch (shape) {
    case "NONE":
      return undefined;
    case "LIST":
      return [];
    case "NUMBER":
      return 0;
    case "RANGE":
      return [0, 0];
    case "BOOLEAN":
      return true;
    case "SALARY":
      return { amount: 0, currency: "EUR", period: "year" };
    case "SALARY_RANGE":
      return { min: 0, max: 0, currency: "EUR", period: "year" };
    default:
      return "";
  }
}

export function shapeOf(meta: Meta, field: string, operator: string): ValueShape {
  const op = meta.fields.find((f) => f.name === field)?.operators.find((o) => o.name === operator);
  return op?.valueShape ?? "TEXT";
}

export function emptyProfile(meta: Meta): ProfileInput {
  return {
    name: "",
    enabled: true,
    sources: ["all"],
    scheduleOverride: null,
    thresholds: { ...meta.defaultThresholds },
    criteria: [],
  };
}

/** The next free id of the form rule-N. Ids stay stable, because match reasons refer to them. */
export function nextRuleId(rules: Criterion[]): string {
  const used = new Set(rules.map((r) => r.id));
  let n = rules.length + 1;
  while (used.has(`rule-${n}`)) {
    n++;
  }
  return `rule-${n}`;
}

export function newRule(meta: Meta, rules: Criterion[]): Criterion {
  const field = meta.fields[0];
  const operator = field.operators[0];
  return {
    id: nextRuleId(rules),
    field: field.name,
    operator: operator.name,
    value: defaultValue(operator.valueShape),
    mode: "prefer",
    weight: 5,
    missingValuePolicy: "ignore",
    label: "",
  };
}

/** Changing the field keeps the operator when it still applies; otherwise it takes the field's first operator. */
export function changeField(rule: Criterion, meta: Meta, field: string): Criterion {
  const info = meta.fields.find((f) => f.name === field);
  if (!info) {
    return rule;
  }
  const keeps = info.operators.some((o) => o.name === rule.operator);
  const operator = keeps ? rule.operator : info.operators[0].name;
  const before = shapeOf(meta, rule.field, rule.operator);
  const after = shapeOf(meta, field, operator);
  return { ...rule, field, operator, value: keeps && before === after ? rule.value : defaultValue(after) };
}

/** The value is kept when the new operator takes the same shape, and reset when it takes another one. */
export function changeOperator(rule: Criterion, meta: Meta, operator: string): Criterion {
  const before = shapeOf(meta, rule.field, rule.operator);
  const after = shapeOf(meta, rule.field, operator);
  return { ...rule, operator, value: before === after ? rule.value : defaultValue(after) };
}

/** Returns a new list with the rule moved by delta places. Out of range moves change nothing. */
export function moveRule<T>(rules: T[], index: number, delta: number): T[] {
  const target = index + delta;
  if (index < 0 || index >= rules.length || target < 0 || target >= rules.length) {
    return rules;
  }
  const next = [...rules];
  const [moved] = next.splice(index, 1);
  next.splice(target, 0, moved);
  return next;
}

export function removeRule<T>(rules: T[], index: number): T[] {
  return rules.filter((_, i) => i !== index);
}

/** A deep, independent copy placed after the original, with its own id. */
export function duplicateRule(rules: Criterion[], index: number): Criterion[] {
  const original = rules[index];
  if (!original) {
    return rules;
  }
  const copy: Criterion = JSON.parse(JSON.stringify(original)) as Criterion;
  const used = new Set(rules.map((r) => r.id));
  let id = `${original.id}-copy`;
  for (let n = 2; used.has(id); n++) {
    id = `${original.id}-copy-${n}`;
  }
  copy.id = id;
  if (original.label && original.label.trim() !== "") {
    copy.label = `${original.label} (copy)`;
  }
  const next = [...rules];
  next.splice(index + 1, 0, copy);
  return next;
}

/** "Name (copy)", then "Name (copy 2)" and so on, avoiding every name in use (case-insensitive). */
export function uniqueName(existing: string[], base: string): string {
  const taken = new Set(existing.map((n) => n.toLowerCase()));
  let candidate = `${base} (copy)`;
  for (let n = 2; taken.has(candidate.toLowerCase()); n++) {
    candidate = `${base} (copy ${n})`;
  }
  return candidate;
}

/** A copy of a stored profile as a new draft: independent, named as a copy, without id and version. */
export function copyOfProfile(profile: SearchProfile, existingNames: string[]): ProfileInput {
  const input = toInput(profile);
  return { ...JSON.parse(JSON.stringify(input)) as ProfileInput, name: uniqueName(existingNames, profile.name) };
}

export function toInput(profile: SearchProfile | ProfileInput): ProfileInput {
  return {
    name: profile.name,
    enabled: profile.enabled,
    sources: [...profile.sources],
    scheduleOverride: profile.scheduleOverride ?? null,
    thresholds: { ...profile.thresholds },
    criteria: profile.criteria.map((c) => ({ ...c })),
  };
}

export function sameInput(a: ProfileInput, b: ProfileInput): boolean {
  return JSON.stringify(a) === JSON.stringify(b);
}

/** Errors of one rule, keyed by the part of the rule: criteria[2].operator becomes { operator }. */
export function rowErrors(errors: Record<string, string>, index: number): Record<string, string> {
  const prefix = `criteria[${index}]`;
  const out: Record<string, string> = {};
  for (const [path, message] of Object.entries(errors)) {
    if (path === prefix) {
      out["row"] = message;
    } else if (path.startsWith(`${prefix}.`)) {
      out[path.slice(prefix.length + 1)] = message;
    }
  }
  return out;
}

/** Errors that belong to the profile itself rather than to a rule. */
export function profileErrors(errors: Record<string, string>): Record<string, string> {
  const out: Record<string, string> = {};
  for (const [path, message] of Object.entries(errors)) {
    if (!path.startsWith("criteria[")) {
      out[path] = message;
    }
  }
  return out;
}

export function parseList(text: string): string[] {
  return text
    .split(",")
    .map((s) => s.trim())
    .filter((s) => s.length > 0);
}

export function formatList(value: unknown): string {
  return Array.isArray(value) ? value.join(", ") : "";
}

/** Import problems as one line each: "criteria[1].weigth: Unknown property". */
export function importProblems(errors: Record<string, string>): string[] {
  const lines = Object.entries(errors).map(([path, message]) => `${path}: ${message}`);
  return lines.length > 0 ? lines : ["The file is not a valid profile."];
}
