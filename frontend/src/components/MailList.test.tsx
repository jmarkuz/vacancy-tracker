import { render } from "preact";
import { act } from "preact/test-utils";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "../api/client";
import * as mailApi from "../api/mail";
import type { ApplicationOption, Mail } from "../api/mail";
import { MailList } from "./MailList";

vi.mock("../api/mail", async (importOriginal) => ({
  ...(await importOriginal<typeof mailApi>()),
  listApplications: vi.fn(),
  relinkMail: vi.fn(),
}));

const listApplications = vi.mocked(mailApi.listApplications);
const relinkMail = vi.mocked(mailApi.relinkMail);

const mail = (patch: Partial<Mail> = {}): Mail => ({
  id: "m1",
  mailKey: "k1",
  folder: "INBOX",
  sender: "jane@recruiting.example",
  senderName: "Jane",
  subject: "Quick question",
  receivedAt: "2026-11-03T10:00:00Z",
  snippet: "Hello",
  category: "recruiter_message",
  vacancyId: null,
  linkReason: null,
  suggestedStatus: null,
  suggestionState: "none",
  createdAt: "2026-11-03T10:00:00Z",
  ...patch,
});

const app = (id: string, title: string, status = "Applied"): ApplicationOption => ({ id, title, company: "Zeta Corp", status });

let host: HTMLElement;
const onChanged = vi.fn();

const button = (label: string) => [...host.querySelectorAll("button")].find((b) => b.textContent?.trim() === label) as HTMLButtonElement;
const select = () => host.querySelector("select") as HTMLSelectElement;

async function flush() {
  await act(async () => {
    await Promise.resolve();
  });
}

async function show(items: Mail[]) {
  await act(async () => {
    render(<MailList items={items} onChanged={onChanged} />, host);
  });
}

beforeEach(() => {
  host = document.createElement("div");
  document.body.appendChild(host);
  relinkMail.mockResolvedValue(mail());
});

afterEach(() => {
  render(null, host);
  host.remove();
  vi.resetAllMocks();
  onChanged.mockReset();
});

describe("MailList: link a mail to an application", () => {
  it("offers the applications and links the mail to the chosen one", async () => {
    listApplications.mockResolvedValue([app("v1", "Backend Engineer"), app("v2", "Platform Engineer")]);
    await show([mail()]);

    await act(async () => button("Link to an application").click());
    await flush();
    expect([...select().options].map((o) => o.textContent)).toEqual([
      "Choose an application…",
      "Backend Engineer · Zeta Corp (Applied)",
      "Platform Engineer · Zeta Corp (Applied)",
    ]);
    await act(async () => {
      select().value = "v2";
      select().dispatchEvent(new Event("change", { bubbles: true }));
    });
    await flush();

    expect(relinkMail).toHaveBeenCalledWith("m1", "v2");
    expect(onChanged).toHaveBeenCalledWith("Mail linked to the application.");
  });

  it("reads the applications again each time linking opens, so a changed list is never stale", async () => {
    listApplications.mockResolvedValueOnce([app("v1", "Backend Engineer")]);
    await show([mail()]);
    await act(async () => button("Link to an application").click());
    await flush();
    expect(select().options).toHaveLength(2);
    await act(async () => button("Cancel").click());

    listApplications.mockResolvedValueOnce([app("v1", "Backend Engineer"), app("v3", "New Role", "Screening")]);
    await act(async () => button("Link to an application").click());
    await flush();

    expect(listApplications).toHaveBeenCalledTimes(2);
    expect([...select().options].map((o) => o.textContent)).toContain("New Role · Zeta Corp (Screening)");
  });

  it("does not offer the application the mail is already linked to, and can unlink", async () => {
    listApplications.mockResolvedValue([app("v1", "Backend Engineer"), app("v2", "Platform Engineer")]);
    await show([mail({ vacancyId: "v1", linkReason: "Linked by you." })]);

    await act(async () => button("Change application").click());
    await flush();
    expect([...select().options].map((o) => o.textContent)).not.toContain("Backend Engineer · Zeta Corp (Applied)");
    await act(async () => button("Cancel").click());
    await act(async () => button("Unlink").click());
    await flush();

    expect(relinkMail).toHaveBeenCalledWith("m1", null);
    expect(onChanged).toHaveBeenCalledWith("Mail unlinked.");
  });

  it("says so when there are no applications yet", async () => {
    listApplications.mockResolvedValue([]);
    await show([mail()]);

    await act(async () => button("Link to an application").click());
    await flush();

    expect(select().options[0].textContent).toBe("No applications yet");
  });

  it("shows an error and closes the chooser when the applications cannot be loaded", async () => {
    listApplications.mockRejectedValue(new Error("offline"));
    await show([mail()]);

    await act(async () => button("Link to an application").click());
    await flush();

    expect(host.querySelector('[role="alert"]')?.textContent).toContain("Could not load your applications.");
    expect(host.querySelector("select")).toBeNull();
  });

  it("shows the server's message when linking is refused", async () => {
    listApplications.mockResolvedValue([app("v1", "Backend Engineer")]);
    relinkMail.mockRejectedValue(new ApiError(404, "Vacancy not found"));
    await show([mail()]);
    await act(async () => button("Link to an application").click());
    await flush();

    await act(async () => {
      select().value = "v1";
      select().dispatchEvent(new Event("change", { bubbles: true }));
    });
    await flush();

    expect(host.querySelector('[role="alert"]')?.textContent).toContain("Vacancy not found");
    expect(onChanged).not.toHaveBeenCalled();
  });

  it("ignores a slow answer from an earlier opening", async () => {
    let resolveFirst: (v: ApplicationOption[]) => void = () => undefined;
    listApplications.mockImplementationOnce(() => new Promise((r) => { resolveFirst = r; }));
    await show([mail()]);
    await act(async () => button("Link to an application").click()); // first opening, still loading
    await act(async () => button("Cancel").click());
    listApplications.mockResolvedValueOnce([app("v9", "Fresh Role")]);
    await act(async () => button("Link to an application").click());
    await flush();

    await act(async () => resolveFirst([app("v1", "Stale Role")]));
    await flush();

    const labels = [...select().options].map((o) => o.textContent ?? "");
    expect(labels.some((l) => l.includes("Fresh Role"))).toBe(true);
    expect(labels.some((l) => l.includes("Stale Role"))).toBe(false);
  });
});
