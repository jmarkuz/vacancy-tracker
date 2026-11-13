import type { MailEvent } from "../api/mail";

const ISO = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2}))?(Z)?$/;
const DAYS = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"];
const MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];
const pad = (n: number): string => String(n).padStart(2, "0");

interface Parts {
  year: number;
  month: number;
  day: number;
  hour: number;
  minute: number;
  utc: boolean;
}

function parse(startsAt: string): Parts | null {
  const m = ISO.exec(startsAt);
  if (!m) {
    return null;
  }
  return { year: +m[1], month: +m[2], day: +m[3], hour: +m[4], minute: +m[5], utc: m[7] === "Z" };
}

/** The moment of the event in this browser: a time with a known zone is converted, a time without one is taken as local. */
function moment(p: Parts): Date {
  return p.utc ? new Date(Date.UTC(p.year, p.month - 1, p.day, p.hour, p.minute)) : new Date(p.year, p.month - 1, p.day, p.hour, p.minute);
}

/**
 * "Tue 12 Nov, 14:30" for the line on a mail card. A time with a known zone is shown in the browser's own zone; a
 * time without one is shown as the mail wrote it. Null when the server sent something unreadable.
 */
export function formatEventTime(event: MailEvent): string | null {
  const p = parse(event.startsAt);
  if (!p) {
    return null;
  }
  const d = moment(p);
  return `${DAYS[d.getDay()]} ${d.getDate()} ${MONTHS[d.getMonth()]}, ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

/** The moment to store as a next action (an ISO instant); a time without a zone is the owner's own local time. */
export function eventInstant(event: MailEvent): string | null {
  const p = parse(event.startsAt);
  return p ? moment(p).toISOString() : null;
}

/** RFC 5545 TEXT: backslash, semicolon and comma are escaped, a line break becomes \n. */
export function escapeIcsText(text: string): string {
  return text.replace(/\\/g, "\\\\").replace(/;/g, "\\;").replace(/,/g, "\\,").replace(/\r\n|\r|\n/g, "\\n");
}

const MAX_OCTETS = 75;
const encoder = new TextEncoder();

/** Folds a content line at 75 octets (never inside a character); each continuation line starts with one space. */
export function foldLine(line: string): string[] {
  const lines: string[] = [];
  let current = "";
  let size = 0;
  for (const ch of line) {
    const n = encoder.encode(ch).length;
    // A continuation line carries its leading space inside the 75 octets.
    const limit = lines.length === 0 ? MAX_OCTETS : MAX_OCTETS - 1;
    if (size + n > limit) {
      lines.push(lines.length === 0 ? current : ` ${current}`);
      current = "";
      size = 0;
    }
    current += ch;
    size += n;
  }
  lines.push(lines.length === 0 ? current : ` ${current}`);
  return lines;
}

function stamp(d: Date): string {
  return `${d.getUTCFullYear()}${pad(d.getUTCMonth() + 1)}${pad(d.getUTCDate())}T${pad(d.getUTCHours())}${pad(d.getUTCMinutes())}${pad(d.getUTCSeconds())}`;
}

export interface IcsInput {
  uid: string;
  summary: string;
  description: string;
  event: MailEvent;
  /** DTSTAMP: when the file was made. */
  now: Date;
  durationMinutes?: number;
}

/**
 * A one-event iCalendar file (RFC 5545): CRLF line endings, lines folded at 75 octets, text escaped. A time with a
 * known zone is written in UTC (with Z); a time without one is "floating" (no Z, no TZID), so the calendar shows it
 * as that wall-clock time wherever the owner is. Null when the event time cannot be read.
 */
export function buildIcs(input: IcsInput): string | null {
  const p = parse(input.event.startsAt);
  if (!p) {
    return null;
  }
  // Wall-clock arithmetic in UTC so that a floating time never shifts with the browser's zone or daylight saving.
  const start = new Date(Date.UTC(p.year, p.month - 1, p.day, p.hour, p.minute));
  const end = new Date(start.getTime() + (input.durationMinutes ?? 60) * 60_000);
  const z = input.event.zoneKnown ? "Z" : "";
  const lines = [
    "BEGIN:VCALENDAR",
    "VERSION:2.0",
    "PRODID:-//Vacancy Tracker//Mail event//EN",
    "CALSCALE:GREGORIAN",
    "METHOD:PUBLISH",
    "BEGIN:VEVENT",
    `UID:${input.uid}`,
    `DTSTAMP:${stamp(input.now)}Z`,
    `DTSTART:${stamp(start)}${z}`,
    `DTEND:${stamp(end)}${z}`,
    `SUMMARY:${escapeIcsText(input.summary)}`,
    `DESCRIPTION:${escapeIcsText(input.description)}`,
    "END:VEVENT",
    "END:VCALENDAR",
  ];
  return lines.flatMap(foldLine).join("\r\n") + "\r\n";
}

/** A safe file name for the download: "interview-2026-11-12.ics". */
export function icsFileName(event: MailEvent): string {
  const p = parse(event.startsAt);
  return p ? `interview-${p.year}-${pad(p.month)}-${pad(p.day)}.ics` : "interview.ics";
}

/** Hands the text to the browser as a file download. */
export function downloadFile(name: string, content: string, type = "text/calendar;charset=utf-8"): void {
  const url = URL.createObjectURL(new Blob([content], { type }));
  const a = document.createElement("a");
  a.href = url;
  a.download = name;
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 0);
}
