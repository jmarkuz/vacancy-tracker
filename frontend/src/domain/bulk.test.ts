import { describe, expect, it } from "vitest";
import type { BulkResult } from "../api/bulk";
import type { Vacancy } from "../api/types";
import { bulkSummary, optionLabel, targetOptions, toggle, togglePage, undoItems, undoSummary } from "./bulk";

const v = (id: string, status: string) => ({ id, status }) as Vacancy;

describe("targetOptions", () => {
  it("lists statuses at least one selected vacancy can move to, with how many", () => {
    const options = targetOptions([v("a", "New"), v("b", "New"), v("c", "Saved")]);
    const byStatus = Object.fromEntries(options.map((o) => [o.status, o.eligible]));
    expect(byStatus.Saved).toBe(2);
    expect(byStatus.Applied).toBe(3);
    expect(byStatus.Withdrawn).toBe(1);
    expect(options.every((o) => o.total === 3)).toBe(true);
  });

  it("puts the most reachable status first and is empty for an empty or closed selection", () => {
    expect(targetOptions([v("a", "New"), v("b", "Saved")])[0].status).toBe("Applied");
    expect(targetOptions([])).toEqual([]);
    expect(targetOptions([v("a", "Accepted")])).toEqual([]);
  });
});

describe("optionLabel", () => {
  it("shows the share only when not everyone can move", () => {
    expect(optionLabel({ status: "Applied", eligible: 3, total: 3 })).toBe("Applied");
    expect(optionLabel({ status: "Saved", eligible: 2, total: 3 })).toBe("Saved (2 of 3)");
  });
});

describe("summaries", () => {
  const result: BulkResult = {
    changed: [{ id: "a", from: "New", wasNew: true }],
    skipped: [{ id: "b", reason: "Rejected cannot move to Saved" }, { id: "c", reason: "Rejected cannot move to Saved" }],
  };

  it("counts moved and skipped, naming each reason once", () => {
    expect(bulkSummary(result, "Saved")).toBe("Moved 1 vacancy to Saved. 2 skipped: Rejected cannot move to Saved.");
  });

  it("says when nothing moved", () => {
    expect(bulkSummary({ changed: [], skipped: result.skipped }, "Saved")).toMatch(/^Nothing moved to Saved\./);
  });

  it("describes an undo, including vacancies that changed since", () => {
    expect(undoSummary({ changed: [{ id: "a", from: "New", wasNew: true }, { id: "b", from: "New", wasNew: false }], skipped: [] })).toBe("Restored 2 vacancies.");
    expect(undoSummary({ changed: [], skipped: [{ id: "a", reason: "x" }] })).toBe("Restored 0 vacancies. 1 changed since and was left as it is.");
  });
});

describe("undoItems", () => {
  it("carries the old status, the status the batch set and the unseen flag", () => {
    expect(undoItems({ changed: [{ id: "a", from: "New", wasNew: true }], skipped: [{ id: "b", reason: "x" }] }, "Saved"))
      .toEqual([{ id: "a", status: "New", expected: "Saved", wasNew: true }]);
  });
});

describe("selection helpers", () => {
  it("toggles one id", () => {
    expect(toggle(["a"], "b")).toEqual(["a", "b"]);
    expect(toggle(["a", "b"], "a")).toEqual(["b"]);
  });

  it("selects all visible rows, keeping earlier selections, and clears them when all are selected", () => {
    expect(togglePage(["z"], ["a", "b"])).toEqual(["z", "a", "b"]);
    expect(togglePage(["z", "a", "b"], ["a", "b"])).toEqual(["z"]);
    expect(togglePage([], [])).toEqual([]);
  });
});
