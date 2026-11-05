import { render } from "preact";
import { act } from "preact/test-utils";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "../api/client";
import type { Mail } from "../api/mail";
import * as vacanciesApi from "../api/vacancies";
import * as calendar from "../domain/calendar";
import { MailList } from "./MailList";

vi.mock("../api/vacancies", () => ({ patchVacancy: vi.fn(), getVacancy: vi.fn() }));
vi.mock("../domain/calendar", async (importOriginal) => ({ ...(await importOriginal<typeof calendar>()), downloadFile: vi.fn() }));

const patchVacancy = vi.mocked(vacanciesApi.patchVacancy);
const getVacancy = vi.mocked(vacanciesApi.getVacancy);
const downloadFile = vi.mocked(calendar.downloadFile);

const event = { startsAt: "2026-11-12T14:30:00", zoneKnown: false, text: "12 November at 14:30" };

const mail = (patch: Partial<Mail> = {}): Mail => ({
  id: "m1",
  mailKey: "k1",
  folder: "INBOX",
  sender: "jane@recruiting.example",
  senderName: "Jane",
  subject: "Invitation",
  receivedAt: "2026-11-03T10:00:00Z",
  snippet: "Hello",
  category: "interview",
  vacancyId: null,
  linkReason: null,
  suggestedStatus: null,
  suggestionState: "none",
  createdAt: "2026-11-03T10:00:00Z",
  jobs: [],
  event,
  ...patch,
});

let host: HTMLElement;

const button = (label: string) => [...host.querySelectorAll("button")].find((b) => b.textContent?.trim() === label) as HTMLButtonElement;

async function flush() {
  await act(async () => {
    await Promise.resolve();
  });
}

async function show(items: Mail[]) {
  await act(async () => {
    render(<MailList items={items} onChanged={vi.fn()} />, host);
  });
}

beforeEach(() => {
  host = document.createElement("div");
  document.body.appendChild(host);
});

afterEach(() => {
  render(null, host);
  host.remove();
  vi.resetAllMocks();
});

describe("MailList: the date of an interview", () => {
  it("shows no event line for a mail without an event", async () => {
    await show([mail({ event: null })]);

    expect(host.querySelector(".mail-event")).toBeNull();
    expect(button("Add to calendar")).toBeUndefined();
  });

  it("shows the date with (local time) for an unknown zone, and without it for a known one", async () => {
    await show([mail()]);
    expect(host.querySelector(".mail-event-when")?.textContent).toContain("Thu 12 Nov, 14:30");
    expect(host.querySelector(".mail-event-when")?.textContent).toContain("(local time)");

    await show([mail({ event: { ...event, startsAt: "2026-11-12T13:30:00Z", zoneKnown: true } })]);
    expect(host.querySelector(".mail-event-when")?.textContent).not.toContain("(local time)");
  });

  it("shows nothing when the time is unreadable", async () => {
    await show([mail({ event: { ...event, startsAt: "garbage" } })]);

    expect(host.querySelector(".mail-event")).toBeNull();
  });

  it("does not offer Set as next action for a mail that is not linked, but still the calendar", async () => {
    await show([mail()]);

    expect(button("Set as next action")).toBeUndefined();
    expect(button("Add to calendar")).toBeDefined();
  });

  it("sets the next action on the linked application and then says so", async () => {
    patchVacancy.mockResolvedValue({} as never);
    await show([mail({ vacancyId: "v1" })]);

    await act(async () => button("Set as next action").click());
    await flush();

    expect(patchVacancy).toHaveBeenCalledWith("v1", {
      nextActionAt: new Date(2026, 10, 12, 14, 30).toISOString(),
      nextActionNote: "Interview — from mail",
    });
    expect(host.querySelector(".mail-event-done")?.textContent).toContain("Next action set");
    expect(button("Set as next action")).toBeUndefined();
  });

  it("words the note for a test task", async () => {
    patchVacancy.mockResolvedValue({} as never);
    await show([mail({ vacancyId: "v1", category: "test_task" })]);

    await act(async () => button("Set as next action").click());
    await flush();

    expect(patchVacancy.mock.calls[0][1].nextActionNote).toBe("Test task — from mail");
  });

  it("shows the error and lets the owner try again", async () => {
    patchVacancy.mockRejectedValueOnce(new ApiError(500, "Server said no"));
    await show([mail({ vacancyId: "v1" })]);

    await act(async () => button("Set as next action").click());
    await flush();

    expect(host.querySelector(".mail-event-error")?.textContent).toBe("Server said no");
    expect(button("Set as next action").disabled).toBe(false);

    patchVacancy.mockRejectedValueOnce(new Error("offline"));
    await act(async () => button("Set as next action").click());
    await flush();
    expect(host.querySelector(".mail-event-error")?.textContent).toBe("Could not set the next action.");
  });

  it("downloads an .ics named after the vacancy title when the mail is linked", async () => {
    getVacancy.mockResolvedValue({ title: "Backend Engineer" } as never);
    await show([mail({ vacancyId: "v1" })]);

    await act(async () => button("Add to calendar").click());
    await flush();

    expect(downloadFile).toHaveBeenCalledOnce();
    const [name, content] = downloadFile.mock.calls[0];
    expect(name).toBe("interview-2026-11-12.ics");
    expect(content).toContain("SUMMARY:Interview: Backend Engineer");
    expect(content).toContain("DTSTART:20261112T143000\r\n");
    expect(content).toContain("DESCRIPTION:From: Jane <jane@recruiting.example>\\nSubject: Invitation");
  });

  it("falls back to the subject when the mail is not linked or the vacancy cannot be read", async () => {
    await show([mail({ subject: "Call, Friday" })]);
    await act(async () => button("Add to calendar").click());
    await flush();
    expect(downloadFile.mock.calls[0][1]).toContain("SUMMARY:Interview: Call\\, Friday");

    getVacancy.mockRejectedValue(new Error("gone"));
    await show([mail({ vacancyId: "v1", subject: "Hello", senderName: null })]);
    await act(async () => button("Add to calendar").click());
    await flush();
    expect(downloadFile.mock.calls[1][1]).toContain("SUMMARY:Interview: Hello");
    expect(downloadFile.mock.calls[1][1]).toContain("DESCRIPTION:From: jane@recruiting.example");
  });
});
