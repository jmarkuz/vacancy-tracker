import { describe, expect, it } from "vitest";
import { EMPTY_FILTER, filterFromParams, filterToParams, inboxCriteria, isActive, parseWords } from "./inboxFilter";

describe("parseWords", () => {
  it("splits at spaces and commas, keeps quoted phrases and drops repeats", () => {
    expect(parseWords(' Java, kotlin  "Spring Boot" java ;remote')).toEqual(["Java", "kotlin", "Spring Boot", "remote"]);
  });

  it("finds nothing in blanks and keeps at most twenty words", () => {
    expect(parseWords("  , ; ")).toEqual([]);
    expect(parseWords(Array.from({ length: 30 }, (_, i) => `w${i}`).join(" "))).toHaveLength(20);
  });
});

describe("inboxCriteria", () => {
  it("asks for every word by default, any word on request, and excludes words", () => {
    expect(inboxCriteria({ words: "java remote", mode: "all", exclude: "" })).toEqual([
      { field: "keywords", operator: "contains_all", value: ["java", "remote"] },
    ]);
    expect(inboxCriteria({ words: "java, kotlin", mode: "any", exclude: "crypto gambling" })).toEqual([
      { field: "keywords", operator: "contains_any", value: ["java", "kotlin"] },
      { field: "keywords", operator: "not_contains", value: ["crypto", "gambling"] },
    ]);
  });

  it("is empty, and inactive, without words", () => {
    expect(inboxCriteria(EMPTY_FILTER)).toEqual([]);
    expect(isActive({ words: " ", mode: "any", exclude: "" })).toBe(false);
    expect(isActive({ words: "", mode: "all", exclude: "crypto" })).toBe(true);
  });
});

describe("filter in the address", () => {
  it("round-trips through the query parameters", () => {
    const f = { words: "java", mode: "any" as const, exclude: "crypto" };
    const params = new URLSearchParams(
      Object.entries(filterToParams(f)).filter((e): e is [string, string] => e[1] !== null),
    );
    expect(params.toString()).toBe("q=java&any=1&not=crypto");
    expect(filterFromParams(params)).toEqual(f);
    expect(filterToParams(EMPTY_FILTER)).toEqual({ q: null, any: null, not: null });
  });
});
