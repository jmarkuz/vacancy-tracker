import { describe, expect, it } from "vitest";
import { postedDays, postedText } from "./age";

const now = new Date("2026-11-04T12:00:00Z");

describe("postedText", () => {
  it("counts whole days", () => {
    expect(postedText("2026-10-25T13:00:00Z", now)).toBe("9 days ago");
    expect(postedText("2026-10-01T00:00:00Z", now)).toBe("34 days ago");
  });

  it("says today and one day in words", () => {
    expect(postedText("2026-11-04T01:00:00Z", now)).toBe("Today");
    expect(postedText("2026-11-03T01:00:00Z", now)).toBe("1 day ago");
  });

  it("says not stated without a date, instead of guessing", () => {
    expect(postedText(null, now)).toBe("Not stated");
    expect(postedText(undefined, now)).toBe("Not stated");
    expect(postedText("", now)).toBe("Not stated");
    expect(postedText("not a date", now)).toBe("Not stated");
  });

  it("never shows a negative age for a date in the future", () => {
    expect(postedText("2026-11-09T00:00:00Z", now)).toBe("Today");
  });
});

describe("postedDays", () => {
  it("is null without a usable date and a whole number otherwise", () => {
    expect(postedDays(null, now)).toBeNull();
    expect(postedDays("2026-10-30T12:00:00Z", now)).toBe(5);
  });
});
