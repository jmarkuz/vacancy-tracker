import { describe, expect, it } from "vitest";
import type { EmployerMatch, MatchStatus } from "../api/analysis";
import { compare, comparisonLine, level } from "./match";

const match = (score: number, statuses: MatchStatus[]): EmployerMatch => ({
  score,
  items: statuses.map((status, i) => ({
    requirementId: `r${i + 1}`,
    text: ["Java", "Kafka", "Kubernetes", "AWS"][i],
    priority: "must",
    status,
    quote: null,
    note: null,
  })),
});

describe("compare", () => {
  it("shows the change and which requirements the tailored text newly covers or still misses", () => {
    const c = compare(match(62, ["covered", "partial", "missing", "missing"]), match(78, ["covered", "covered", "covered", "missing"]));
    expect(c).toEqual({ base: 62, tailored: 78, delta: 16, newlyCovered: ["Kafka", "Kubernetes"], stillMissing: ["AWS"] });
    expect(comparisonLine(c)).toBe("Base 62% → Tailored 78% (+16)");
  });

  it("reports a drop and no change as well", () => {
    expect(comparisonLine(compare(match(70, ["covered"]), match(60, ["partial"])), "Version 2")).toBe("Base 70% → Version 2 60% (−10)");
    expect(comparisonLine(compare(match(50, ["partial"]), match(50, ["partial"])))).toBe("Base 50% → Tailored 50% (±0)");
  });
});

describe("level", () => {
  it("names the level in words", () => {
    expect([level(10), level(40), level(69), level(70)]).toEqual(["low", "medium", "medium", "high"]);
  });
});
