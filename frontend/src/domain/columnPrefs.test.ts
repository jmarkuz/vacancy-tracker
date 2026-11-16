import { describe, expect, it } from "vitest";
import { resolveColumnIds, sameColumns } from "./columnPrefs";

const KNOWN = ["title", "company", "status", "location", "salary", "tags"];
const DEFAULTS = ["title", "company", "status"];

describe("resolveColumnIds", () => {
  it("keeps the table's order, not the order of the list", () => {
    expect(resolveColumnIds("tags,company,title", KNOWN, DEFAULTS)).toEqual(["title", "company", "tags"]);
  });

  it("drops unknown ids, duplicates, blanks and spaces", () => {
    expect(resolveColumnIds(" company ,,company,gone,status", KNOWN, DEFAULTS)).toEqual(["title", "company", "status"]);
  });

  it("falls back to the defaults for nothing, an empty list or only unknown ids", () => {
    for (const raw of [null, undefined, "", ",", "gone,other", []]) {
      expect(resolveColumnIds(raw, KNOWN, DEFAULTS)).toEqual(["title", "company", "status"]);
    }
  });

  it("always shows the title, which holds the button that opens the vacancy", () => {
    expect(resolveColumnIds("salary", KNOWN, DEFAULTS)).toEqual(["title", "salary"]);
    expect(resolveColumnIds(["tags"], KNOWN, DEFAULTS)).toEqual(["title", "tags"]);
  });
});

describe("sameColumns", () => {
  it("treats lists that give the same table as the same", () => {
    expect(sameColumns("company,gone", ["company", "company"], KNOWN, DEFAULTS)).toBe(true);
    expect(sameColumns([], null, KNOWN, DEFAULTS)).toBe(true);
    expect(sameColumns("title,company,status", null, KNOWN, DEFAULTS)).toBe(true);
  });

  it("tells different tables apart", () => {
    expect(sameColumns("salary", null, KNOWN, DEFAULTS)).toBe(false);
  });
});
