import { describe, expect, it } from "vitest";
import { DEFAULT_SORT, parseSort } from "./sort";

describe("parseSort", () => {
  it("reads key and direction", () => {
    expect(parseSort("title:desc")).toEqual({ key: "title", direction: "desc", value: "title:desc" });
    expect(parseSort("salaryMax:asc")).toEqual({ key: "salaryMax", direction: "asc", value: "salaryMax:asc" });
  });

  it("takes a key without a direction as ascending, as the server does", () => {
    expect(parseSort("title")).toEqual({ key: "title", direction: "asc", value: "title:asc" });
  });

  it("ignores the case of the direction", () => {
    expect(parseSort("title:DESC").direction).toBe("desc");
  });

  it("falls back to the default for nothing, an unknown key or an unknown direction", () => {
    for (const raw of [null, "", "password:asc", "title:sideways", ":asc"]) {
      expect(parseSort(raw).value).toBe(DEFAULT_SORT);
    }
    expect(parseSort(null)).toEqual({ key: "lastSeenAt", direction: "desc", value: "lastSeenAt:desc" });
  });
});
