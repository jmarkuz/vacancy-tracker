import { render } from "preact";
import { act } from "preact/test-utils";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import * as mailApi from "../api/mail";
import type { Mail } from "../api/mail";
import { settle } from "./loadFailed.helpers";
import { VacancyMailPanel } from "./VacancyMailPanel";

vi.mock("../api/mail", async (importOriginal) => ({ ...(await importOriginal<typeof mailApi>()), listMail: vi.fn() }));
const listMail = vi.mocked(mailApi.listMail);

const mail = (id: string): Mail => ({
  id,
  mailKey: id,
  folder: "INBOX",
  sender: "jane@recruiting.example",
  senderName: "Jane",
  subject: `Subject ${id}`,
  receivedAt: "2026-11-03T10:00:00Z",
  snippet: "Hello",
  category: "interview",
  vacancyId: "v1",
  linkReason: "Linked by you.",
  suggestedStatus: null,
  suggestionState: "none",
  createdAt: "2026-11-03T10:00:00Z",
  jobs: [],
});

let host: HTMLElement;

beforeEach(() => {
  host = document.createElement("div");
  document.body.appendChild(host);
});

afterEach(() => {
  render(null, host);
  host.remove();
  vi.resetAllMocks();
});

const show = async () => {
  await act(async () => {
    render(<VacancyMailPanel vacancyId="v1" onStatusChanged={() => undefined} />, host);
  });
  await settle();
};

describe("VacancyMailPanel", () => {
  it("lists the mail of the application", async () => {
    listMail.mockResolvedValue([mail("a"), mail("b")]);

    await show();

    expect(host.textContent).toContain("Mail about this application");
    expect(host.textContent).toContain("Subject a");
    expect(host.textContent).toContain("Subject b");
    expect(host.querySelector('[role="alert"]')).toBeNull();
  });

  it("shows nothing when there is no mail, and no error", async () => {
    listMail.mockResolvedValue([]);

    await show();

    expect(host.textContent).toBe("");
  });

  it("says it could not load the mail, instead of looking empty, and loads again on Try again", async () => {
    listMail.mockRejectedValueOnce(new Error("offline"));
    await show();
    expect(host.querySelector('[role="alert"]')?.textContent).toContain("Could not load the mail about this application.");

    listMail.mockResolvedValueOnce([mail("a")]);
    await act(async () => {
      (host.querySelector('[role="alert"] button') as HTMLButtonElement).click();
    });
    await settle();

    expect(host.querySelector('[role="alert"]')).toBeNull();
    expect(host.textContent).toContain("Subject a");
    expect(listMail).toHaveBeenCalledTimes(2);
  });

  it("does not report an error for a request that was cancelled by leaving the application", async () => {
    let rejectFirst: (e: Error) => void = () => undefined;
    listMail.mockImplementationOnce(() => new Promise((_, reject) => { rejectFirst = reject; }));
    await act(async () => {
      render(<VacancyMailPanel vacancyId="v1" onStatusChanged={() => undefined} />, host);
    });
    listMail.mockResolvedValueOnce([mail("z")]);
    await act(async () => {
      render(<VacancyMailPanel vacancyId="v2" onStatusChanged={() => undefined} />, host);
    });
    await settle();

    await act(async () => rejectFirst(new DOMException("aborted", "AbortError")));
    await settle();

    expect(host.querySelector('[role="alert"]')).toBeNull();
    expect(host.textContent).toContain("Subject z");
  });
});
