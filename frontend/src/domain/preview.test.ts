import { describe, expect, it } from "vitest";
import type { Preview, PreviewSample } from "../api/profiles";
import { previewSummary, sampleLabel } from "./preview";

const base: Preview = { total: 1200, applicable: 1200, matched: 800, inbox: 400, filteredOut: 400, samples: [] };

describe("previewSummary", () => {
  it("states matches, inbox and filtered counts with thousands separators", () => {
    expect(previewSummary(base)).toBe("Of 1,200 existing vacancies, 800 would match, 400 of them would land in the Inbox. 400 would be filtered out.");
  });

  it("mentions the source scope only when it narrows the set", () => {
    expect(previewSummary({ ...base, applicable: 300 })).toContain("(300 come from this profile's sources)");
  });

  it("says so when there are no vacancies", () => {
    expect(previewSummary({ ...base, total: 0, applicable: 0 })).toMatch(/nothing to preview/);
  });
});

describe("sampleLabel", () => {
  const s = (kind: PreviewSample["kind"], score: number | null): PreviewSample =>
    ({ vacancyId: "1", title: "t", company: "c", kind, score, reasons: [] });

  it("names where each sample would land", () => {
    expect(sampleLabel(s("inbox", 90))).toBe("Inbox, score 90");
    expect(sampleLabel(s("below-threshold", 10))).toBe("Below the Inbox threshold, score 10");
    expect(sampleLabel(s("filtered-out", null))).toBe("Filtered out");
  });
});
