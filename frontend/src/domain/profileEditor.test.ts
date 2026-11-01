import { describe, expect, it } from "vitest";
import type { Criterion, Meta, SearchProfile } from "../api/profiles";
import {
  changeField,
  changeOperator,
  copyOfProfile,
  defaultValue,
  duplicateRule,
  emptyProfile,
  formatList,
  moveRule,
  newRule,
  nextRuleId,
  parseList,
  profileErrors,
  removeRule,
  rowErrors,
  sameInput,
  toInput,
  uniqueName,
} from "./profileEditor";

const meta: Meta = {
  fields: [
    {
      name: "title",
      kind: "text",
      operators: [
        { name: "contains_any", valueShape: "LIST" },
        { name: "equals", valueShape: "TEXT" },
        { name: "regex", valueShape: "PATTERN" },
        { name: "exists", valueShape: "NONE" },
      ],
    },
    {
      name: "salary",
      kind: "salary",
      operators: [
        { name: "gte", valueShape: "SALARY" },
        { name: "between", valueShape: "SALARY_RANGE" },
      ],
    },
    {
      name: "postedAge",
      kind: "number",
      operators: [
        { name: "lte", valueShape: "NUMBER" },
        { name: "between", valueShape: "RANGE" },
      ],
    },
  ],
  modes: ["require", "prefer", "avoid", "exclude"],
  missingValuePolicies: ["ignore", "penalize", "hide"],
  minWeight: 1,
  maxWeight: 10,
  defaultThresholds: { inboxMinScore: 40, notifyMinScore: 70, maxAgeDays: 60 },
};

const rule = (id: string, extra: Partial<Criterion> = {}): Criterion => ({
  id,
  field: "title",
  operator: "contains_any",
  value: ["java"],
  mode: "prefer",
  weight: 5,
  missingValuePolicy: "ignore",
  label: "",
  ...extra,
});

describe("moveRule", () => {
  it("moves a rule up or down without changing the original list", () => {
    const rules = [rule("a"), rule("b"), rule("c")];

    expect(moveRule(rules, 1, -1).map((r) => r.id)).toEqual(["b", "a", "c"]);
    expect(moveRule(rules, 1, 1).map((r) => r.id)).toEqual(["a", "c", "b"]);
    expect(rules.map((r) => r.id)).toEqual(["a", "b", "c"]);
  });

  it("changes nothing at the ends or for a bad index", () => {
    const rules = [rule("a"), rule("b")];

    expect(moveRule(rules, 0, -1)).toBe(rules);
    expect(moveRule(rules, 1, 1)).toBe(rules);
    expect(moveRule(rules, 5, -1)).toBe(rules);
  });
});

describe("duplicateRule", () => {
  it("inserts an independent deep copy after the original with its own id", () => {
    const original = rule("kw", { value: ["java", "kotlin"], label: "Keyword" });
    const rules = duplicateRule([original, rule("other")], 0);

    expect(rules.map((r) => r.id)).toEqual(["kw", "kw-copy", "other"]);
    expect(rules[1].label).toBe("Keyword (copy)");
    (rules[1].value as string[]).push("go");
    expect(original.value).toEqual(["java", "kotlin"]);
    expect(rules[0].value).toEqual(["java", "kotlin"]);
  });

  it("finds a free id when copies already exist, and leaves an empty label empty", () => {
    const rules = duplicateRule([rule("kw"), rule("kw-copy")], 0);

    expect(rules.map((r) => r.id)).toEqual(["kw", "kw-copy-2", "kw-copy"]);
    expect(rules[1].label).toBe("");
  });

  it("returns the list unchanged for a bad index", () => {
    const rules = [rule("a")];
    expect(duplicateRule(rules, 3)).toBe(rules);
  });
});

describe("rule ids", () => {
  it("generates ids that are not in use, even after deletions", () => {
    expect(nextRuleId([])).toBe("rule-1");
    expect(nextRuleId([rule("rule-1"), rule("rule-2")])).toBe("rule-3");
    // rule-1 was deleted: the list has one rule, but its id rule-2 is taken, so the next one is rule-3.
    expect(nextRuleId([rule("rule-2")])).toBe("rule-3");
  });
});

describe("newRule", () => {
  it("starts from the first field and operator with a value of the right shape", () => {
    const r = newRule(meta, [rule("rule-1")]);

    expect(r.id).toBe("rule-2");
    expect(r.field).toBe("title");
    expect(r.operator).toBe("contains_any");
    expect(r.value).toEqual([]);
    expect(r.mode).toBe("prefer");
    expect(r.weight).toBe(5);
    expect(r.missingValuePolicy).toBe("ignore");
  });
});

describe("changeField and changeOperator", () => {
  it("keeps the operator and the value when the new field allows the same", () => {
    const changed = changeField(rule("a", { field: "title", operator: "equals", value: "abc" }), meta, "title");
    expect(changed.value).toBe("abc");
  });

  it("falls back to the first operator of the new field and resets the value", () => {
    const changed = changeField(rule("a"), meta, "salary");

    expect(changed.field).toBe("salary");
    expect(changed.operator).toBe("gte");
    expect(changed.value).toEqual({ amount: 0, currency: "EUR", period: "year" });
  });

  it("keeps the value when the operator has the same shape and resets it otherwise", () => {
    const base = rule("a", { operator: "contains_any", value: ["x"] });

    expect(changeOperator(base, meta, "contains_any").value).toEqual(["x"]);
    expect(changeOperator(base, meta, "equals").value).toBe("");
    expect(changeOperator(base, meta, "exists").value).toBeUndefined();
  });

  it("ignores an unknown field", () => {
    const base = rule("a");
    expect(changeField(base, meta, "nope")).toBe(base);
  });
});

describe("defaultValue", () => {
  it("gives every shape a starting value", () => {
    expect(defaultValue("NONE")).toBeUndefined();
    expect(defaultValue("LIST")).toEqual([]);
    expect(defaultValue("TEXT")).toBe("");
    expect(defaultValue("PATTERN")).toBe("");
    expect(defaultValue("NUMBER")).toBe(0);
    expect(defaultValue("RANGE")).toEqual([0, 0]);
    expect(defaultValue("BOOLEAN")).toBe(true);
    expect(defaultValue("SALARY_RANGE")).toEqual({ min: 0, max: 0, currency: "EUR", period: "year" });
  });
});

describe("profile copies", () => {
  const stored: SearchProfile = {
    id: "p1",
    version: 3,
    updatedAt: "2026-11-01T10:00:00Z",
    name: "Remote",
    enabled: true,
    sources: ["all"],
    scheduleOverride: null,
    thresholds: { inboxMinScore: 40, notifyMinScore: 70, maxAgeDays: 60 },
    criteria: [rule("kw", { value: ["java"] })],
  };

  it("names copies so that no name repeats", () => {
    expect(uniqueName(["Remote"], "Remote")).toBe("Remote (copy)");
    expect(uniqueName(["Remote", "Remote (copy)"], "Remote")).toBe("Remote (copy 2)");
    expect(uniqueName(["remote (COPY)"], "Remote")).toBe("Remote (copy 2)");
  });

  it("makes an independent draft without id and version", () => {
    const copy = copyOfProfile(stored, ["Remote"]);

    expect(copy.name).toBe("Remote (copy)");
    expect("id" in copy).toBe(false);
    expect("version" in copy).toBe(false);
    (copy.criteria[0].value as string[]).push("go");
    copy.thresholds.inboxMinScore = 90;
    expect(stored.criteria[0].value).toEqual(["java"]);
    expect(stored.thresholds.inboxMinScore).toBe(40);
  });

  it("compares drafts by content", () => {
    expect(sameInput(toInput(stored), toInput(stored))).toBe(true);
    const changed = toInput(stored);
    changed.enabled = false;
    expect(sameInput(toInput(stored), changed)).toBe(false);
  });
});

describe("errors by row", () => {
  const errors = {
    name: "Name is required",
    "thresholds.maxAgeDays": "Must be between 1 and 3650",
    "criteria[1].operator": "'regex' is not allowed for field 'salary'",
    "criteria[1].value": "A value is required",
    "criteria[2]": "A criterion is required",
  };

  it("splits row errors by the part of the rule", () => {
    expect(rowErrors(errors, 1)).toEqual({
      operator: "'regex' is not allowed for field 'salary'",
      value: "A value is required",
    });
    expect(rowErrors(errors, 2)).toEqual({ row: "A criterion is required" });
    expect(rowErrors(errors, 0)).toEqual({});
  });

  it("keeps profile level errors apart", () => {
    expect(profileErrors(errors)).toEqual({ name: "Name is required", "thresholds.maxAgeDays": "Must be between 1 and 3650" });
  });
});

describe("lists and new profiles", () => {
  it("parses and formats comma lists", () => {
    expect(parseList(" a, b ,, c ")).toEqual(["a", "b", "c"]);
    expect(parseList("   ")).toEqual([]);
    expect(formatList(["a", "b"])).toBe("a, b");
    expect(formatList(undefined)).toBe("");
  });

  it("starts a new profile with the server's default thresholds and all sources", () => {
    const p = emptyProfile(meta);

    expect(p.sources).toEqual(["all"]);
    expect(p.thresholds).toEqual(meta.defaultThresholds);
    expect(p.thresholds).not.toBe(meta.defaultThresholds);
    expect(p.criteria).toEqual([]);
  });

  it("removes a rule by index", () => {
    expect(removeRule([rule("a"), rule("b")], 0).map((r) => r.id)).toEqual(["b"]);
  });
});
