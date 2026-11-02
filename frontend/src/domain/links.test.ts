import { describe, expect, it } from "vitest";
import { safeLink } from "./links";

describe("safeLink", () => {
  it("keeps http and https links", () => {
    expect(safeLink("https://jobs.example.com/1")).toBe("https://jobs.example.com/1");
    expect(safeLink(" http://example.com ")).toBe("http://example.com/");
  });

  it("drops script, data and other schemes, and text that is not a link", () => {
    expect(safeLink("javascript:alert(1)")).toBeNull();
    expect(safeLink("JavaScript:alert(1)")).toBeNull();
    expect(safeLink("data:text/html,<b>x</b>")).toBeNull();
    expect(safeLink("mailto:hr@example.com")).toBeNull();
    expect(safeLink("not a link")).toBeNull();
    expect(safeLink(null)).toBeNull();
    expect(safeLink("")).toBeNull();
  });
});
