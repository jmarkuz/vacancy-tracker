import { Fragment, h, render } from "preact";
import { describe, expect, it } from "vitest";
import { clip, COLUMNS, DEFAULT_COLUMNS, formatDate, formatSalary } from "./columns";
import { postedAgeLabel, priorityLabel, scoreLabel, sourcesText, tagsText, visaLabel, workModeLabel } from "../domain/cells";
import { SORT_KEYS } from "../domain/sort";
import type { Vacancy } from "../api/types";

function vacancy(overrides: Partial<Vacancy>): Vacancy {
  return {
    id: "1",
    title: "t",
    company: "c",
    url: null,
    canonicalUrl: null,
    description: null,
    notes: null,
    contact: null,
    nextActionNote: null,
    userEditedFields: [],
    location: null,
    country: null,
    workMode: null,
    status: "New",
    priority: null,
    salaryMin: null,
    salaryMax: null,
    currency: null,
    salaryPeriod: null,
    tags: [],
    nextActionAt: null,
    firstSeenAt: "2026-11-01T00:00:00Z",
    lastSeenAt: "2026-11-01T00:00:00Z",
    isNew: true,
    ...overrides,
  };
}

describe("formatSalary", () => {
  it("says Not specified when no salary is stated, never a dash or 0", () => {
    expect(formatSalary(vacancy({}))).toBe("Not specified");
  });

  it("shows a range with currency and period", () => {
    const text = formatSalary(vacancy({ salaryMin: 70000, salaryMax: 85000, currency: "EUR", salaryPeriod: "year" }));
    expect(text).toContain("–");
    expect(text).toMatch(/EUR \/ year$/);
  });

  it("shows a single amount when min and max are equal or only one is set", () => {
    expect(formatSalary(vacancy({ salaryMin: 50000, salaryMax: 50000 }))).not.toContain("–");
    expect(formatSalary(vacancy({ salaryMax: 60000 }))).not.toContain("–");
  });

  it("says what a one-sided salary means instead of passing it off as exact", () => {
    expect(formatSalary(vacancy({ salaryMin: 50000, currency: "EUR", salaryPeriod: "year" }))).toMatch(/^from 50.000 EUR \/ year$/);
    expect(formatSalary(vacancy({ salaryMax: 60000, currency: "EUR" }))).toMatch(/^up to 60.000 EUR$/);
  });

  it("treats 0 and negative amounts as unknown, never as pay", () => {
    expect(formatSalary(vacancy({ salaryMin: 0, salaryMax: 0, currency: "USD" }))).toBe("Not specified");
    expect(formatSalary(vacancy({ salaryMin: 0, salaryMax: 90000, currency: "USD" }))).toMatch(/^up to 90.000 USD$/);
    expect(formatSalary(vacancy({ salaryMin: -5, salaryMax: null }))).toBe("Not specified");
  });

  it("keeps the cents of an hourly rate instead of rounding 45.5 to 46", () => {
    const text = formatSalary(vacancy({ salaryMin: 45.5, salaryMax: 60, currency: "USD", salaryPeriod: "hour" }));
    expect(text).toContain("45.5");
    expect(text).toMatch(/USD \/ hour$/);
  });

  it("writes a short period out the same way for every spelling a source may use", () => {
    for (const [given, shown] of [["hourly", "hour"], ["Monthly", "month"], ["annual", "year"], ["WEEK", "week"], ["day", "day"]]) {
      expect(formatSalary(vacancy({ salaryMax: 100, currency: "USD", salaryPeriod: given }))).toMatch(new RegExp(`USD / ${shown}$`));
    }
  });

  it("shows an unknown period as given and leaves out a missing or blank currency and period", () => {
    expect(formatSalary(vacancy({ salaryMax: 100, currency: "USD", salaryPeriod: "sprint" }))).toMatch(/USD \/ sprint$/);
    expect(formatSalary(vacancy({ salaryMax: 100, currency: " ", salaryPeriod: " " }))).toBe("up to 100");
  });

  it("puts the smaller amount first when a source gave them the wrong way round, and upper-cases the currency", () => {
    expect(formatSalary(vacancy({ salaryMin: 90000, salaryMax: 70000, currency: "eur" }))).toMatch(/^70.000–90.000 EUR$/);
  });

  it("shows a large amount in full", () => {
    expect(formatSalary(vacancy({ salaryMin: 1234567, salaryMax: 1234567, currency: "JPY" }))).toMatch(/^1.234.567 JPY$/);
  });
});

describe("workModeLabel", () => {
  it("writes the three modes the collection stores the same way whatever the source spelled", () => {
    expect(workModeLabel("remote")).toBe("Remote");
    expect(workModeLabel("hybrid")).toBe("Hybrid");
    expect(workModeLabel("onsite")).toBe("On-site");
    for (const given of ["On-site", "on site", "ON_SITE", " Onsite "]) {
      expect(workModeLabel(given)).toBe("On-site");
    }
    expect(workModeLabel("REMOTE")).toBe("Remote");
  });

  it("shows any other value as given with a capital, and nothing as a dash", () => {
    expect(workModeLabel("flexible")).toBe("Flexible");
    expect(workModeLabel(null)).toBe("-");
    expect(workModeLabel(undefined)).toBe("-");
    expect(workModeLabel("  ")).toBe("-");
  });
});

describe("priorityLabel", () => {
  it("names the three priorities and shows nothing as a dash", () => {
    expect(priorityLabel(1)).toBe("1 · high");
    expect(priorityLabel(2)).toBe("2 · medium");
    expect(priorityLabel(3)).toBe("3 · low");
    expect(priorityLabel(null)).toBe("-");
  });

  it("shows a number outside 1 to 3 as it is", () => {
    expect(priorityLabel(7)).toBe("7");
    expect(priorityLabel(Number.NaN)).toBe("-");
  });
});

describe("tagsText", () => {
  it("joins the tags, skips blank ones and shows no tags as a dash", () => {
    expect(tagsText(["remote", " ", "java"])).toBe("remote, java");
    expect(tagsText([])).toBe("-");
    expect(tagsText(undefined)).toBe("-");
  });
});

describe("scoreLabel, visaLabel, sourcesText, postedAgeLabel", () => {
  it("shows an unscored vacancy as a dash, never 0, and a score as a whole number", () => {
    expect(scoreLabel(null)).toBe("-");
    expect(scoreLabel(undefined)).toBe("-");
    expect(scoreLabel(0)).toBe("0");
    expect(scoreLabel(86.6)).toBe("87");
    expect(scoreLabel(Number.NaN)).toBe("-");
  });

  it("tells offered, not offered and not stated apart", () => {
    expect(visaLabel(true)).toBe("Offered");
    expect(visaLabel(false)).toBe("Not offered");
    expect(visaLabel(null)).toBe("-");
    expect(visaLabel(undefined)).toBe("-");
  });

  it("lists each source once, capitalised, and a vacancy added by hand as a dash", () => {
    expect(sourcesText(["greenhouse", "remotive", "greenhouse"])).toBe("Greenhouse, Remotive");
    expect(sourcesText([])).toBe("-");
    expect(sourcesText(undefined)).toBe("-");
    expect(sourcesText([" "])).toBe("-");
  });

  it("counts whole days of a posting and shows no or an unreadable date as a dash", () => {
    const now = new Date("2026-11-16T12:00:00Z");
    expect(postedAgeLabel("2026-11-16T01:00:00Z", now)).toBe("Today");
    expect(postedAgeLabel("2026-11-15T01:00:00Z", now)).toBe("1 day");
    expect(postedAgeLabel("2026-11-01T12:00:00Z", now)).toBe("15 days");
    expect(postedAgeLabel("2027-01-01T00:00:00Z", now)).toBe("Today");
    expect(postedAgeLabel(null, now)).toBe("-");
    expect(postedAgeLabel("nonsense", now)).toBe("-");
    expect(postedAgeLabel("2026-11-01T12:00:00Z")).toMatch(/days$/);
  });
});

describe("COLUMNS", () => {
  /** The text a cell shows. */
  const shown = (id: string, overrides: Partial<Vacancy>) => {
    const host = document.createElement("div");
    render(h(Fragment, null, COLUMNS.find((c) => c.id === id)!.render(vacancy(overrides))), host);
    return host.textContent;
  };

  it("has unique ids, and every sort key is one the server accepts", () => {
    const ids = COLUMNS.map((c) => c.id);
    expect(new Set(ids).size).toBe(ids.length);
    for (const c of COLUMNS) {
      if (c.sortKey) {
        expect(SORT_KEYS as readonly string[]).toContain(c.sortKey);
      }
    }
  });

  it("keeps the default columns among the known ones, in order", () => {
    const ids = COLUMNS.map((c) => c.id);
    expect(DEFAULT_COLUMNS.every((d) => ids.includes(d))).toBe(true);
    expect(DEFAULT_COLUMNS.map((d) => ids.indexOf(d))).toEqual([...DEFAULT_COLUMNS.map((d) => ids.indexOf(d))].sort((a, b) => a - b));
  });

  it("renders every column for a vacancy with nothing but the required fields", () => {
    for (const c of COLUMNS) {
      expect(() => shown(c.id, { tags: undefined as never })).not.toThrow();
    }
  });

  it("shows the optional facts, and a dash where the vacancy has none", () => {
    expect(shown("country", { country: "de" })).toBe("DE");
    expect(shown("country", { country: null })).toBe("-");
    expect(shown("matchScore", { matchScore: 72 })).toBe("72");
    expect(shown("matchScore", {})).toBe("-");
    expect(shown("visa", { visaSponsorship: true })).toBe("Offered");
    expect(shown("visa", { visaSponsorship: false })).toBe("Not offered");
    expect(shown("visa", {})).toBe("-");
    expect(shown("sources", { sources: ["lever"] })).toBe("Lever");
    expect(shown("sources", {})).toBe("-");
    expect(shown("ghost", {})).toBe("-");
    expect(shown("ghost", { ghost: { likely: true, signs: 2, reasons: ["posted long ago"] } })).toBe("Likely ghost");
    expect(shown("ghost", { ghost: { likely: false, signs: 0, reasons: [] } })).toBe("-");
    expect(shown("firstSeenAt", {})).not.toBe("-");
    expect(shown("postedAge", {})).toBe("-");
  });

  it("names a blank title instead of leaving the row without a way to open it", () => {
    expect(shown("title", { title: "  " })).toBe("(untitled)");
  });
});

describe("clip", () => {
  it("shows a blank text as a dash and keeps the full text in the tooltip", () => {
    const blank = clip("   ") as { props: { children: string; title: string } };
    expect(blank.props.children).toBe("-");
    const text = clip(" Berlin ") as { props: { children: string; title: string } };
    expect(text.props.title).toBe("Berlin");
  });
});

describe("formatDate", () => {
  it("shows a dash for no date and for text that is not a date, instead of throwing", () => {
    expect(formatDate(null)).toBe("-");
    expect(formatDate("")).toBe("-");
    expect(formatDate("not a date")).toBe("-");
  });

  it("formats a real timestamp", () => {
    expect(formatDate("2026-11-01T10:00:00Z")).not.toBe("-");
  });
});
