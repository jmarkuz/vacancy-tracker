import { describe, expect, it } from "vitest";
import type { SyncResult } from "../api/mail";
import { formatClock, intervalPhrase, intervalStepIndex, intervalTick, SYNC_INTERVAL_STEPS, syncSummary } from "./mail";

const result = (patch: Partial<SyncResult>): SyncResult => ({ configured: true, seen: 0, stored: 0, linked: 0, suggested: 0, error: null, ...patch });

describe("syncSummary", () => {
  it("counts what the read found", () => {
    expect(syncSummary(result({ seen: 12, stored: 3, linked: 2, suggested: 2 }))).toBe("Read 12 mails: 3 about jobs, 2 linked, 2 suggestions.");
  });

  it("uses the singular for one mail and one suggestion", () => {
    expect(syncSummary(result({ seen: 1, stored: 1, linked: 1, suggested: 1 }))).toBe("Read 1 mail: 1 about jobs, 1 linked, 1 suggestion.");
  });

  it("says so when nothing was found", () => {
    expect(syncSummary(result({}))).toBe("Read 0 mails: 0 about jobs, 0 linked, 0 suggestions.");
  });
});

describe("mail display helpers", () => {
  it("joins the company and the place, leaving out what is missing", async () => {
    const { jobMeta } = await import("./mail");
    expect(jobMeta({ company: "Elastic", location: "Greece" })).toBe("Elastic · Greece");
    expect(jobMeta({ company: "Elastic", location: null })).toBe("Elastic");
    expect(jobMeta({ company: null, location: "  " })).toBe("");
  });

  it("counts the vacancies in a digest", async () => {
    const { digestHeading } = await import("./mail");
    expect(digestHeading(4)).toBe("4 vacancies in this mail");
    expect(digestHeading(1)).toBe("1 vacancy in this mail");
  });

  it("follows only plain web addresses", async () => {
    const { safeHref } = await import("./mail");
    expect(safeHref("https://www.linkedin.com/jobs/view/1/")).toBe("https://www.linkedin.com/jobs/view/1/");
    expect(safeHref("http://example.com")).toBe("http://example.com");
    expect(safeHref("javascript:alert(1)")).toBeNull();
    expect(safeHref(null)).toBeNull();
  });

  it("splits a text into paragraphs and says when it is long", async () => {
    const { bodyParagraphs } = await import("./mail");
    expect(bodyParagraphs("Hi\n\nThanks\n")).toEqual({ paragraphs: ["Hi", "Thanks"], long: false });
    expect(bodyParagraphs("a\nb\nc\nd\ne").long).toBe(true);
    expect(bodyParagraphs("x".repeat(400)).long).toBe(true);
    expect(bodyParagraphs(null)).toEqual({ paragraphs: [], long: false });
  });
});

describe("the sync interval steps", () => {
  it("offer 5, 10, 15, 30, 45 and 60 minutes", () => {
    expect([...SYNC_INTERVAL_STEPS]).toEqual([5, 10, 15, 30, 45, 60]);
  });

  it("find the index of a step, and the nearest one for any other value", () => {
    expect(SYNC_INTERVAL_STEPS.map(intervalStepIndex)).toEqual([0, 1, 2, 3, 4, 5]);
    expect(intervalStepIndex(1)).toBe(0);
    expect(intervalStepIndex(38)).toBe(4);
    expect(intervalStepIndex(500)).toBe(5);
  });

  it("are worded for the text, the value and the tick labels", () => {
    expect(intervalPhrase(15)).toBe("every 15 minutes");
    expect(intervalPhrase(60)).toBe("every hour");
    expect(intervalTick(5)).toBe("5 min");
    expect(intervalTick(60)).toBe("1 h");
  });

  it("show the clock time of the next check", () => {
    expect(formatClock(new Date(2026, 10, 5, 10, 45).toISOString())).toMatch(/10:45/);
  });
});
