import { describe, expect, it } from "vitest";
import { hasMailFilters, linkedParam, mailFilterQuery, NO_MAIL_FILTERS, parseMailFilters, toggleCategory } from "./mailFilter";

describe("parseMailFilters", () => {
  it("reads nothing as no filters", () => {
    expect(parseMailFilters(new URLSearchParams(""))).toEqual(NO_MAIL_FILTERS);
  });

  it("reads kinds in the order of the chips, ignores unknown ones and reads linked and q", () => {
    const f = parseMailFilters(new URLSearchParams("cat=rejection, bogus,offer&linked=false&q=acme corp"));
    expect(f).toEqual({ categories: ["offer", "rejection"], linked: "unlinked", q: "acme corp" });
    expect(parseMailFilters(new URLSearchParams("linked=true")).linked).toBe("linked");
    expect(parseMailFilters(new URLSearchParams("linked=maybe")).linked).toBe("all");
  });
});

describe("mailFilterQuery", () => {
  it("removes the parameters of empty filters", () => {
    expect(mailFilterQuery(NO_MAIL_FILTERS)).toEqual({ cat: null, linked: null, q: null });
    expect(mailFilterQuery({ categories: [], linked: "all", q: "   " }).q).toBeNull();
  });

  it("writes the chosen filters", () => {
    expect(mailFilterQuery({ categories: ["offer", "interview"], linked: "linked", q: "x" })).toEqual({ cat: "offer,interview", linked: "true", q: "x" });
    expect(mailFilterQuery({ categories: [], linked: "unlinked", q: "" }).linked).toBe("false");
  });
});

describe("helpers", () => {
  it("knows when a filter is on", () => {
    expect(hasMailFilters(NO_MAIL_FILTERS)).toBe(false);
    expect(hasMailFilters({ ...NO_MAIL_FILTERS, categories: ["offer"] })).toBe(true);
    expect(hasMailFilters({ ...NO_MAIL_FILTERS, linked: "linked" })).toBe(true);
    expect(hasMailFilters({ ...NO_MAIL_FILTERS, q: "a" })).toBe(true);
    expect(hasMailFilters({ ...NO_MAIL_FILTERS, q: "  " })).toBe(false);
  });

  it("toggles a kind in chip order and ignores an unknown one", () => {
    expect(toggleCategory(["rejection"], "offer")).toEqual(["offer", "rejection"]);
    expect(toggleCategory(["offer", "rejection"], "offer")).toEqual(["rejection"]);
    expect(toggleCategory(["offer"], "nope")).toEqual(["offer"]);
  });

  it("maps linked to the server parameter", () => {
    expect(linkedParam("all")).toBeUndefined();
    expect(linkedParam("linked")).toBe(true);
    expect(linkedParam("unlinked")).toBe(false);
  });
});
