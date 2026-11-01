import { describe, expect, it } from "vitest";
import { project } from "./ViewBar";

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
