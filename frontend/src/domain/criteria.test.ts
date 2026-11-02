import { describe, expect, it } from "vitest";
import { describe as describeCriterion, parseCriteria, serializeCriteria, toValue, needsInput, operatorKey, specFor } from "./criteria";

describe("parseCriteria", () => {
  it("returns an empty list for missing or malformed input", () => {
    expect(parseCriteria(null)).toEqual([]);
    expect(parseCriteria("")).toEqual([]);
    expect(parseCriteria("not json")).toEqual([]);
    expect(parseCriteria('{"field":"title"}')).toEqual([]);
  });

  it("drops entries that are not criterion-shaped", () => {
    const raw = JSON.stringify([{ field: "title", operator: "equals", value: "x" }, { field: 3 }, null]);
    expect(parseCriteria(raw)).toEqual([{ field: "title", operator: "equals", value: "x" }]);
  });
});

describe("serializeCriteria", () => {
  it("writes null for an empty list so the URL stays clean", () => {
    expect(serializeCriteria([])).toBeNull();
  });

  it("round-trips through parseCriteria", () => {
    const list = [{ field: "tags", operator: "contains_any", value: ["java", "kotlin"] }];
    expect(parseCriteria(serializeCriteria(list))).toEqual(list);
  });
});

describe("toValue", () => {
  it("splits comma lists and trims items", () => {
    expect(toValue("list", " Saved, Applied ,, ")).toEqual(["Saved", "Applied"]);
    expect(toValue("list", " , ")).toBeUndefined();
  });

  it("parses numbers and rejects non-numbers", () => {
    expect(toValue("number", "42")).toBe(42);
    expect(toValue("number", "abc")).toBeUndefined();
  });

  it("converts local date input to an ISO instant", () => {
    const value = toValue("date", "2026-11-01T10:30");
    expect(typeof value).toBe("string");
    expect(new Date(value as string).toISOString()).toBe(value);
  });

  it("returns undefined for empty text and for operators without a value", () => {
    expect(toValue("text", "   ")).toBeUndefined();
    expect(toValue("none", "ignored")).toBeUndefined();
  });
});

describe("describe", () => {
  it("renders a chip label from the field table", () => {
    expect(describeCriterion({ field: "status", operator: "in", value: ["Saved", "Applied"] })).toBe(
      "Status is one of Saved, Applied",
    );
  });

  it("renders operators without a value", () => {
    expect(describeCriterion({ field: "nextActionAt", operator: "exists" })).toBe("Next action is set");
  });

  it("falls back to raw names for unknown fields", () => {
    expect(describeCriterion({ field: "mystery", operator: "equals", value: "x" })).toBe("mystery equals x");
  });
});

describe("yes/no fields", () => {
  it("carry their value in the operator and need no input", () => {
    expect(toValue("yes", "")).toBe(true);
    expect(toValue("no", "anything")).toBe(false);
    expect(needsInput("yes")).toBe(false);
    expect(needsInput("none")).toBe(false);
    expect(needsInput("text")).toBe(true);
  });

  it("describe a visa filter in words, not as true or false", () => {
    expect(describeCriterion({ field: "visaSponsorship", operator: "equals", value: true })).toBe("Visa sponsorship is offered");
    expect(describeCriterion({ field: "visaSponsorship", operator: "equals", value: false })).toBe("Visa sponsorship is not offered");
    expect(describeCriterion({ field: "visaSponsorship", operator: "not_exists" })).toBe("Visa sponsorship is not stated");
  });

  it("tell apart two choices with the same operator by key", () => {
    const visa = specFor("visaSponsorship")!;
    expect(new Set(visa.operators.map(operatorKey)).size).toBe(visa.operators.length);
  });
});
