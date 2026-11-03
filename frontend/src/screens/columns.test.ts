import { describe, expect, it } from "vitest";
import { formatSalary } from "./columns";
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
});
