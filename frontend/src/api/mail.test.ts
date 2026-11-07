import { describe, expect, it, vi } from "vitest";
import { CATEGORY_LABEL, type Mail, setMailInterval, startMailReread, suggestionText } from "./mail";

const mail = (patch: Partial<Mail>): Mail => ({
  id: "m",
  mailKey: "k",
  folder: "INBOX",
  sender: "hr@acme.example",
  senderName: "Acme",
  subject: "Interview",
  receivedAt: null,
  snippet: null,
  category: "interview",
  vacancyId: "v",
  linkReason: null,
  suggestedStatus: "Interview",
  suggestionState: "pending",
  createdAt: "2026-11-02T10:00:00Z",
  jobs: [],
  ...patch,
});

describe("mail", () => {
  it("asks about a pending suggestion only", () => {
    expect(suggestionText(mail({}))).toBe("Move to Interview?");
    expect(suggestionText(mail({ suggestionState: "accepted" }))).toBeNull();
    expect(suggestionText(mail({ suggestedStatus: null, suggestionState: "none" }))).toBeNull();
  });

  it("names every stored category", () => {
    for (const code of ["rejection", "offer", "test_task", "interview", "application_viewed", "application_received", "recruiter_message", "other_job"]) {
      expect(CATEGORY_LABEL[code]).toBeTruthy();
    }
  });
});

describe("startMailReread", () => {
  it("posts to the re-read endpoint and returns what the server says", async () => {
    const fetchMock = vi.fn(async () => ({ ok: true, status: 202, json: async () => ({ started: true, reason: null }), text: async () => "" }));
    vi.stubGlobal("fetch", fetchMock);

    await expect(startMailReread()).resolves.toEqual({ started: true, reason: null });

    expect((fetchMock.mock.calls[0] as unknown as [string, RequestInit])[0]).toBe("/api/mail/reread");
    expect((fetchMock.mock.calls[0] as unknown as [string, RequestInit])[1].method).toBe("POST");
    vi.unstubAllGlobals();
  });
});

describe("setMailInterval", () => {
  it("puts the minutes to the settings endpoint", async () => {
    const fetchMock = vi.fn(async () => ({ ok: true, status: 200, json: async () => ({ intervalMinutes: 45 }), text: async () => "" }));
    vi.stubGlobal("fetch", fetchMock);

    await expect(setMailInterval(45)).resolves.toEqual({ intervalMinutes: 45 });

    const [url, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toBe("/api/mail/settings");
    expect(init.method).toBe("PUT");
    expect(init.body).toBe('{"intervalMinutes":45}');
    vi.unstubAllGlobals();
  });
});
