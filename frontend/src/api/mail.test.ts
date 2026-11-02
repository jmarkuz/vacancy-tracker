import { describe, expect, it } from "vitest";
import { CATEGORY_LABEL, type Mail, suggestionText } from "./mail";

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
