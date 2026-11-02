import { describe, expect, it } from "vitest";
import type { RecalculationProgress } from "../api/recalculation";
import { percentDone, progressText } from "./recalc";

const base: RecalculationProgress = {
  jobId: "j",
  state: "RUNNING",
  processed: 0,
  total: 0,
  failed: 0,
  startedAt: "2026-11-01T10:00:00Z",
  finishedAt: null,
};

describe("percentDone", () => {
  it("is the floored share, capped at 100", () => {
    expect(percentDone({ ...base, processed: 1, total: 3 })).toBe(33);
    expect(percentDone({ ...base, processed: 3, total: 3 })).toBe(100);
    expect(percentDone({ ...base, processed: 9, total: 3 })).toBe(100);
  });

  it("treats an empty job as done only when it has completed", () => {
    expect(percentDone({ ...base, total: 0, state: "RUNNING" })).toBe(0);
    expect(percentDone({ ...base, total: 0, state: "COMPLETED" })).toBe(100);
  });
});

describe("progressText", () => {
  it("shows counts and percent while running", () => {
    expect(progressText({ ...base, processed: 1200, total: 4000 })).toBe("Recalculating: 1,200 of 4,000 (30%)");
  });

  it("says it is counting before the total is known", () => {
    expect(progressText({ ...base, processed: 0, total: 0 })).toBe("Recalculating: counting vacancies…");
  });

  it("summarizes a finished job with correct plural", () => {
    expect(progressText({ ...base, state: "COMPLETED", processed: 1, total: 1 })).toBe("Scores recalculated for 1 vacancy");
    expect(progressText({ ...base, state: "COMPLETED", processed: 5, total: 5 })).toBe("Scores recalculated for 5 vacancies");
  });

  it("reports vacancies that could not be scored", () => {
    expect(progressText({ ...base, state: "COMPLETED", processed: 10, total: 10, failed: 2 })).toBe(
      "Scores recalculated for 10 vacancies; 2 could not be scored",
    );
  });
});
