import { describe, expect, it, vi } from "vitest";
import type { MailEvent } from "../api/mail";
import { buildIcs, downloadFile, escapeIcsText, eventInstant, foldLine, formatEventTime, icsFileName } from "./calendar";

const local: MailEvent = { startsAt: "2026-11-12T14:30:00", zoneKnown: false, text: "12 November at 14:30" };
const utc: MailEvent = { startsAt: "2026-11-12T13:30:00Z", zoneKnown: true, text: "12 November at 14:30 CET" };
const now = new Date("2026-11-05T09:15:30Z");

describe("buildIcs", () => {
  it("writes a floating local time with no Z and a one hour default", () => {
    const ics = buildIcs({ uid: "m1@vacancy-tracker", summary: "Interview: Backend Engineer", description: "From: Jane\nSubject: Hi", event: local, now });

    expect(ics).toBe(
      [
        "BEGIN:VCALENDAR",
        "VERSION:2.0",
        "PRODID:-//Vacancy Tracker//Mail event//EN",
        "CALSCALE:GREGORIAN",
        "METHOD:PUBLISH",
        "BEGIN:VEVENT",
        "UID:m1@vacancy-tracker",
        "DTSTAMP:20261105T091530Z",
        "DTSTART:20261112T143000",
        "DTEND:20261112T153000",
        "SUMMARY:Interview: Backend Engineer",
        "DESCRIPTION:From: Jane\\nSubject: Hi",
        "END:VEVENT",
        "END:VCALENDAR",
        "",
      ].join("\r\n"),
    );
  });

  it("writes a known zone as UTC with Z", () => {
    const ics = buildIcs({ uid: "u", summary: "s", description: "d", event: utc, now }) ?? "";

    expect(ics).toContain("DTSTART:20261112T133000Z\r\n");
    expect(ics).toContain("DTEND:20261112T143000Z\r\n");
  });

  it("carries the end over midnight and honours a duration", () => {
    const late: MailEvent = { startsAt: "2026-12-31T23:30:00", zoneKnown: false, text: "x" };

    const ics = buildIcs({ uid: "u", summary: "s", description: "d", event: late, now, durationMinutes: 90 }) ?? "";

    expect(ics).toContain("DTEND:20270101T010000\r\n");
  });

  it("uses CRLF only and escapes commas, semicolons, backslashes and new lines", () => {
    const ics = buildIcs({ uid: "u", summary: "A, B; C\\D", description: "line1\nline2\r\nline3", event: local, now }) ?? "";

    expect(ics).toContain("SUMMARY:A\\, B\\; C\\\\D\r\n");
    expect(ics).toContain("DESCRIPTION:line1\\nline2\\nline3\r\n");
    expect(ics.replace(/\r\n/g, "")).not.toMatch(/[\r\n]/);
    expect(ics.endsWith("END:VCALENDAR\r\n")).toBe(true);
  });

  it("folds long lines at 75 octets", () => {
    const ics = buildIcs({ uid: "u", summary: "x".repeat(200), description: "d", event: local, now }) ?? "";
    const lines = ics.split("\r\n");

    expect(lines.every((l) => new TextEncoder().encode(l).length <= 75)).toBe(true);
    expect(ics.replace(/\r\n /g, "")).toContain(`SUMMARY:${"x".repeat(200)}\r\n`);
  });

  it("answers null for an unreadable time", () => {
    expect(buildIcs({ uid: "u", summary: "s", description: "d", event: { startsAt: "soon", zoneKnown: false, text: "" }, now })).toBeNull();
  });
});

describe("foldLine", () => {
  it("keeps a short line whole", () => {
    expect(foldLine("short")).toEqual(["short"]);
  });

  it("breaks at exactly 75 octets and continues with a space inside the limit", () => {
    expect(foldLine("a".repeat(150))).toEqual(["a".repeat(75), ` ${"a".repeat(74)}`, " a"]);
  });

  it("never splits a multi-byte character", () => {
    const folded = foldLine("Я".repeat(60));

    for (const l of folded) {
      expect(new TextEncoder().encode(l).length).toBeLessThanOrEqual(75);
    }
    expect(folded.map((l, i) => (i === 0 ? l : l.slice(1))).join("")).toBe("Я".repeat(60));
  });
});

describe("escapeIcsText", () => {
  it("escapes backslash, semicolon, comma and new line", () => {
    expect(escapeIcsText("a\\b;c,d\ne")).toBe("a\\\\b\\;c\\,d\\ne");
  });
});

describe("formatEventTime and eventInstant", () => {
  it("shows a local time as written", () => {
    expect(formatEventTime(local)).toBe("Thu 12 Nov, 14:30");
    expect(eventInstant(local)).toBe(new Date(2026, 10, 12, 14, 30).toISOString());
  });

  it("converts a known zone to the browser zone", () => {
    expect(eventInstant(utc)).toBe("2026-11-12T13:30:00.000Z");
    const d = new Date("2026-11-12T13:30:00Z");
    expect(formatEventTime(utc)).toBe(`Thu 12 Nov, ${String(d.getHours()).padStart(2, "0")}:${String(d.getMinutes()).padStart(2, "0")}`);
  });

  it("gives null for unreadable input", () => {
    const bad: MailEvent = { startsAt: "tomorrow", zoneKnown: false, text: "" };
    expect(formatEventTime(bad)).toBeNull();
    expect(eventInstant(bad)).toBeNull();
    expect(icsFileName(bad)).toBe("interview.ics");
  });

  it("names the file after the day", () => {
    expect(icsFileName(local)).toBe("interview-2026-11-12.ics");
  });
});

describe("downloadFile", () => {
  it("clicks a temporary link to a blob and cleans up", () => {
    vi.useFakeTimers();
    const create = vi.fn(() => "blob:x");
    const revoke = vi.fn();
    Object.assign(URL, { createObjectURL: create, revokeObjectURL: revoke });
    const click = vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(() => undefined);

    downloadFile("a.ics", "BEGIN");
    vi.runAllTimers();

    expect(create).toHaveBeenCalledOnce();
    expect(click).toHaveBeenCalledOnce();
    expect(revoke).toHaveBeenCalledWith("blob:x");
    expect(document.querySelector("a[download]")).toBeNull();
    click.mockRestore();
    vi.useRealTimers();
  });
});
