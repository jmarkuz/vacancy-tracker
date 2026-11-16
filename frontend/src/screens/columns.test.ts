import { describe, expect, it } from "vitest";
import { formatDate, formatSalary } from "./columns";
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
