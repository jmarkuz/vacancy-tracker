import { render } from "preact";
import { act } from "preact/test-utils";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { Vacancy } from "../api/types";
import { InboxInspector } from "./InboxInspector";

const vacancy = (patch: Partial<Vacancy> = {}): Vacancy =>
  ({
    id: "v1",
    title: "Backend Engineer",
    company: "Zeta Corp",
    url: "https://jobs.example/1",
    canonicalUrl: "https://jobs.example/1",
    location: "Prague",
    country: "CZ",
    workMode: "onsite",
    description: "Plain",
    salaryMin: null,
    salaryMax: null,
    currency: null,
    salaryPeriod: null,
    status: "New",
    priority: null,
    notes: null,
    contact: null,
    nextActionNote: null,
    tags: [],
    nextActionAt: null,
    userEditedFields: [],
    firstSeenAt: "2026-11-01T10:00:00Z",
    lastSeenAt: "2026-11-02T10:00:00Z",
    isNew: true,
    visaSponsorship: true,
    ...patch,
  }) as Vacancy;

let host: HTMLElement;

beforeEach(() => {
  vi.useFakeTimers();
  vi.setSystemTime(new Date("2026-11-04T12:00:00Z"));
  host = document.createElement("div");
  document.body.appendChild(host);
});

afterEach(() => {
  render(null, host);
  host.remove();
  vi.useRealTimers();
});

const show = (v: Vacancy) =>
  act(() => {
    render(<InboxInspector vacancy={v} fit={undefined} busy={false} refreshKey={0} onSave={() => undefined} onSkip={() => undefined} onDetails={() => undefined} />, host);
  });

const value = (label: string) => {
  const dt = [...host.querySelectorAll("dt")].find((d) => d.textContent === label);
  return dt?.nextElementSibling?.textContent;
};

describe("InboxInspector: the conditions", () => {
  it("shows how long ago the source says the posting was published", () => {
    show(vacancy({ postedAt: "2026-10-25T13:00:00Z" }));

    expect(value("Posted")).toBe("9 days ago");
    expect(value("Location")).toBe("Prague");
    expect(value("Visa sponsorship")).toBe("Offered");
  });

  it("says not stated when the source gave no date, instead of guessing", () => {
    show(vacancy({ postedAt: null }));

    expect(value("Posted")).toBe("Not stated");
  });

  it("treats an old API answer without the field as not stated", () => {
    show(vacancy());

    expect(value("Posted")).toBe("Not stated");
  });
});
