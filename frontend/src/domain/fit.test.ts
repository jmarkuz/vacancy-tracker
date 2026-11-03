import { describe, expect, it } from "vitest";
import { fitSummary, fitTier, fitsById, parseKeywords, skillCount, skillsLabel } from "./fit";

const fit = {
  vacancyId: "v1",
  resumeId: "r1",
  resumeRevision: 1,
  score: 76,
  matched: ["Java"],
  missing: ["AWS"],
  reasons: [
    { kind: "matched", text: "Matches 5 of 7 skills the vacancy names: Java." },
    { kind: "missing", text: "Not in your resume: AWS." },
    { kind: "title", text: "The title mentions a skill from your resume." },
  ],
  computedAt: "2026-11-02T10:00:00Z",
};

describe("fit", () => {
  it("tiers scores", () => {
    expect(fitTier(70)).toBe("high");
    expect(fitTier(69)).toBe("mid");
    expect(fitTier(40)).toBe("mid");
    expect(fitTier(39)).toBe("low");
  });

  it("summarises the skills (not a percentage) with the first two reasons", () => {
    expect(fitSummary(fit)).toBe(
      "1 of 2 skills the vacancy names are in your resume. Matches 5 of 7 skills the vacancy names: Java.. Not in your resume: AWS.",
    );
  });

  it("counts skills as \"N of M\", and says when there is nothing to compare", () => {
    expect(skillCount(fit)).toEqual({ matched: 1, total: 2 });
    expect(skillsLabel({ ...fit, matched: ["Java", "Kafka"], missing: ["AWS"] })).toBe("2 of 3 skills");
    const none = { ...fit, matched: [], missing: [] };
    expect(skillCount(none)).toBeNull();
    expect(skillsLabel(none)).toBe("Not enough data");
    expect(fitSummary(none)).toMatch(/^Not enough data/);
  });

  it("indexes fits by vacancy", () => {
    expect(fitsById([fit]).v1.score).toBe(76);
  });

  it("parses typed keywords without blanks or repeats", () => {
    expect(parseKeywords("Java, spring boot;\n Kafka ,, java", ["Spring Boot"])).toEqual(["Spring Boot", "Java", "Kafka"]);
  });
});
