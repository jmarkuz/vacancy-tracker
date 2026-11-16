import { describe, expect, it } from "vitest";
import { project, savedColumns } from "./ViewBar";

describe("savedColumns", () => {
  it("stores the columns the table shows: no stale ids, no repeats, the title always", () => {
    expect(savedColumns("salary,gone,salary")).toEqual(["title", "salary"]);
  });

  it("stores nothing when the address has no choice, so the view keeps following the defaults", () => {
    expect(savedColumns(null)).toEqual([]);
    expect(savedColumns("")).toEqual([]);
  });
});

describe("project", () => {
  it("keeps only field, operator and value, so server-only fields do not count as changes", () => {
    const stored = [{ id: null, field: "title", operator: "equals", value: "x", mode: null, weight: null }];
    expect(project(stored as never)).toEqual([{ field: "title", operator: "equals", value: "x" }]);
  });

  it("drops the value key for operators without one", () => {
    expect(project([{ field: "nextActionAt", operator: "exists", value: undefined }])).toEqual([
      { field: "nextActionAt", operator: "exists" },
    ]);
  });
});
