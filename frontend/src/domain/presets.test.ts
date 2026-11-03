import { describe, expect, it } from "vitest";
import { placeholders, shares, weightsProblem } from "./presets";

describe("placeholders", () => {
  it("lists each mark once, in order, without the braces", () => {
    expect(placeholders("Target roles: {{for example, Senior Backend Engineer}}\nSeniority: {{level}} {{level}}")).toEqual([
      "for example, Senior Backend Engineer",
      "level",
    ]);
  });

  it("finds nothing in a filled text", () => {
    expect(placeholders("Senior backend roles, remote in the EU.")).toEqual([]);
  });
});

describe("shares", () => {
  it("normalises weights that do not add up to 100", () => {
    expect(shares([{ weight: 3 }, { weight: 1 }, { weight: 0 }])).toEqual([75, 25, 0]);
  });

  it("gives zero shares when every weight is zero", () => {
    expect(shares([{ weight: 0 }, { weight: 0 }])).toEqual([0, 0]);
  });
});

describe("weightsProblem", () => {
  it("accepts named, distinct, whole weights with a positive one", () => {
    expect(weightsProblem([{ name: "Stack", weight: 2 }, { name: "Pay", weight: 0 }])).toBeNull();
  });

  it("explains what is wrong", () => {
    expect(weightsProblem([])).toMatch(/at least one/);
    expect(weightsProblem([{ name: " ", weight: 1 }])).toMatch(/name/);
    expect(weightsProblem([{ name: "Stack", weight: 1 }, { name: "stack", weight: 1 }])).toMatch(/twice/);
    expect(weightsProblem([{ name: "Stack", weight: 1.5 }])).toMatch(/whole/);
    expect(weightsProblem([{ name: "Stack", weight: 0 }])).toMatch(/above zero/);
  });
});
