import { describe, expect, it } from "vitest";
import type { Meta } from "../api/profiles";
import { emptyAnswers, wizardErrors, wizardProfile } from "./wizard";

const meta = { defaultThresholds: { inboxMinScore: 40, notifyMinScore: 70, maxAgeDays: 60 }, fields: [] } as unknown as Meta;
const answers = (patch: Partial<ReturnType<typeof emptyAnswers>>) => ({ ...emptyAnswers(), ...patch });

describe("wizardErrors", () => {
  it("requires a name and at least one keyword", () => {
    expect(Object.keys(wizardErrors(emptyAnswers()))).toEqual(["name", "keywords"]);
  });

  it("is happy with the minimum", () => {
    expect(wizardErrors(answers({ name: "P", keywords: "a" }))).toEqual({});
  });

  it("checks the salary amount and currency only when a salary is given", () => {
    expect(wizardErrors(answers({ name: "P", keywords: "a", salaryAmount: "-1", currency: "EU" })))
      .toEqual({ salaryAmount: "Enter a positive number", currency: "Enter a three-letter currency code" });
  });
});

describe("wizardProfile", () => {
  it("builds one keyword rule and the server's default thresholds from the minimum", () => {
    const p = wizardProfile(answers({ name: " Mine ", keywords: "alpha, beta" }), meta);
    expect(p.name).toBe("Mine");
    expect(p.sources).toEqual(["all"]);
    expect(p.thresholds.inboxMinScore).toBe(40);
    expect(p.criteria).toEqual([{ id: "rule-1", field: "keywords", operator: "contains_any", value: ["alpha", "beta"], mode: "prefer", weight: 8, missingValuePolicy: "ignore", label: "Looked-for words" }]);
  });

  it("adds work mode, country and salary rules only for the answers given, with sequential ids", () => {
    const p = wizardProfile(answers({ name: "P", keywords: "a", workModes: ["remote"], countries: "aa, bb", salaryAmount: "50000", currency: "xxx", period: "month" }), meta);
    expect(p.criteria.map((c) => c.id)).toEqual(["rule-1", "rule-2", "rule-3", "rule-4"]);
    expect(p.criteria[2].value).toEqual(["AA", "BB"]);
    expect(p.criteria[3].value).toEqual({ amount: 50000, currency: "XXX", period: "month" });
  });

  it("never filters: every rule is prefer", () => {
    const p = wizardProfile(answers({ name: "P", keywords: "a", workModes: ["remote"], countries: "aa", salaryAmount: "1", currency: "xxx" }), meta);
    expect(new Set(p.criteria.map((c) => c.mode))).toEqual(new Set(["prefer"]));
  });
});
