import { render } from "preact";
import { act } from "preact/test-utils";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "../api/client";
import * as mailApi from "../api/mail";
import type { Mail, MailStatus } from "../api/mail";
import { DELETE_UNDO_MS } from "../domain/mail";
import { MailScreen } from "./MailScreen";

vi.mock("../api/mail", async (importOriginal) => ({
  ...(await importOriginal<typeof mailApi>()),
  getMailStatus: vi.fn(),
  listMail: vi.fn(),
  deleteMail: vi.fn(),
  deleteOldMail: vi.fn(),
}));

const getStatus = vi.mocked(mailApi.getMailStatus);
const listMail = vi.mocked(mailApi.listMail);
const deleteMail = vi.mocked(mailApi.deleteMail);
const deleteOldMail = vi.mocked(mailApi.deleteOldMail);

const NOW = Date.parse("2026-11-20T12:00:00Z");
const mail = (id: string, receivedAt = "2026-11-19T10:00:00Z"): Mail => ({
  id,
  mailKey: `k-${id}`,
  folder: "INBOX",
  sender: "jane@recruiting.example",
  senderName: "Jane",
  subject: `Subject ${id}`,
  receivedAt,
  snippet: "Hello",
  category: "recruiter_message",
  vacancyId: null,
  linkReason: null,
  suggestedStatus: null,
  suggestionState: "none",
  createdAt: receivedAt,
  jobs: [],
});
const status: MailStatus = { configured: true, folders: ["INBOX"], pending: 0, lastSync: null, lastError: null, syncing: false, lastResult: null };

let host: HTMLElement;

const settle = async (ms = 0) => {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(ms);
  });
};
const button = (label: RegExp | string) =>
  [...host.querySelectorAll("button")].find((b) => (typeof label === "string" ? b.textContent?.trim() === label : label.test(b.textContent ?? ""))) as HTMLButtonElement;
const click = async (el: HTMLElement) => {
  await act(async () => {
    el.click();
  });
  await settle();
};
const dialog = () => host.querySelector('[role="alertdialog"]') as HTMLElement | null;
const subjects = () => [...host.querySelectorAll(".mail-list > li")].map((li) => li.textContent ?? "");
const key = async (target: Element, k: string, init: KeyboardEventInit = {}) => {
  await act(async () => {
    target.dispatchEvent(new KeyboardEvent("keydown", { key: k, bubbles: true, cancelable: true, ...init }));
  });
};

async function mount() {
  await act(async () => {
    render(<MailScreen />, host);
  });
  await settle();
}

/** Ticks the checkbox of the mail with this subject. */
const select = async (subject: string) => {
  const li = [...host.querySelectorAll(".mail-list > li")].find((x) => x.textContent?.includes(subject));
  await click(li?.querySelector('input[type="checkbox"]') as HTMLElement);
};

beforeEach(() => {
  vi.useFakeTimers({ now: NOW });
  host = document.createElement("div");
  document.body.appendChild(host);
  getStatus.mockResolvedValue(status);
  listMail.mockResolvedValue([mail("a"), mail("b"), mail("c")]);
  deleteMail.mockResolvedValue({ deleted: 2 });
  deleteOldMail.mockResolvedValue({ deleted: 1 });
});

afterEach(() => {
  render(null, host);
  host.remove();
  vi.useRealTimers();
  vi.resetAllMocks();
});

describe("MailScreen: delete asks in the app, then waits for Undo", () => {
  it("does not use the native confirm and opens a dialog with focus on the confirming button", async () => {
    const confirm = vi.fn(() => true);
    vi.stubGlobal("confirm", confirm);
    await mount();
    await select("Subject a");

    await click(button(/Delete selected/));

    expect(confirm).not.toHaveBeenCalled();
    expect(dialog()?.textContent).toContain("Delete 1 mail?");
    expect(dialog()?.getAttribute("aria-modal")).toBe("true");
    expect(document.activeElement).toBe(button("Delete"));
    vi.unstubAllGlobals();
  });

  it("Cancel and Escape close it without deleting, and focus goes back to the button", async () => {
    await mount();
    await select("Subject a");
    const open = button(/Delete selected/);
    open.focus();

    await click(open);
    await click(button("Cancel"));
    expect(dialog()).toBeNull();
    expect(document.activeElement).toBe(open);

    await click(open);
    await key(dialog() as HTMLElement, "Escape");
    expect(dialog()).toBeNull();
    expect(document.activeElement).toBe(open);

    await settle(DELETE_UNDO_MS * 2);
    expect(deleteMail).not.toHaveBeenCalled();
    expect(subjects()).toHaveLength(3);
  });

  it("a click on the scrim cancels, a click inside the card does not", async () => {
    await mount();
    await select("Subject a");
    await click(button(/Delete selected/));

    await act(async () => {
      (dialog() as HTMLElement).dispatchEvent(new MouseEvent("mousedown", { bubbles: true }));
    });
    expect(dialog()).not.toBeNull();

    await act(async () => {
      (host.querySelector(".confirm-scrim") as HTMLElement).dispatchEvent(new MouseEvent("mousedown", { bubbles: true }));
    });
    expect(dialog()).toBeNull();
  });

  it("keeps Tab inside the dialog", async () => {
    await mount();
    await select("Subject a");
    await click(button(/Delete selected/));
    const confirmButton = button("Delete");
    const cancel = button("Cancel");

    // Tab from the last button wraps to the first, Shift+Tab from the first wraps to the last.
    await key(confirmButton, "Tab");
    expect(document.activeElement).toBe(cancel);
    await key(cancel, "Tab", { shiftKey: true });
    expect(document.activeElement).toBe(confirmButton);
    // Tab inside the dialog (not at an end) is left to the browser.
    await key(cancel, "Tab");
    expect(dialog()).not.toBeNull();
  });

  it("Enter on the focused confirming button confirms once; the mails are hidden and the delete waits", async () => {
    await mount();
    await select("Subject a");
    await select("Subject b");
    await click(button(/Delete selected/));

    await key(document.activeElement as Element, "Enter");

    expect(dialog()).toBeNull();
    expect(subjects()).toHaveLength(1);
    expect(host.querySelector(".toast")?.textContent).toContain("Deleting 2 mails…");
    expect(button("Undo")).toBeDefined();
    expect(deleteMail).not.toHaveBeenCalled();

    listMail.mockResolvedValue([mail("c")]);
    await settle(DELETE_UNDO_MS - 1);
    expect(deleteMail).not.toHaveBeenCalled();
    await settle(1);

    expect(deleteMail).toHaveBeenCalledTimes(1);
    expect(deleteMail).toHaveBeenCalledWith(["a", "b"]);
    await settle();
    expect(host.querySelector(".toast")?.textContent).toContain("Deleted 2 mails.");
    expect(subjects()).toHaveLength(1);
  });

  it("the confirming button also works with a click", async () => {
    await mount();
    await select("Subject a");
    await click(button(/Delete selected/));
    await click(button("Delete"));

    await settle(DELETE_UNDO_MS);

    expect(deleteMail).toHaveBeenCalledWith(["a"]);
  });

  it("Undo cancels the delete and brings the mails back, selected again", async () => {
    await mount();
    await select("Subject a");
    await select("Subject b");
    await click(button(/Delete selected/));
    await click(button("Delete"));
    expect(subjects()).toHaveLength(1);

    await click(button("Undo"));

    expect(subjects()).toHaveLength(3);
    expect(host.querySelector(".toast")).toBeNull();
    expect(button(/Delete selected \(2\)/)).toBeDefined();
    await settle(DELETE_UNDO_MS * 2);
    expect(deleteMail).not.toHaveBeenCalled();
  });

  it("sends the waiting delete at once when the screen is left", async () => {
    await mount();
    await select("Subject a");
    await click(button(/Delete selected/));
    await click(button("Delete"));
    expect(deleteMail).not.toHaveBeenCalled();

    await act(async () => {
      render(null, host);
    });

    expect(deleteMail).toHaveBeenCalledWith(["a"]);
    await settle(DELETE_UNDO_MS * 2);
    expect(deleteMail).toHaveBeenCalledTimes(1);
  });

  it("sends the waiting delete at once when another delete starts", async () => {
    await mount();
    await select("Subject a");
    await click(button(/Delete selected/));
    await click(button("Delete"));

    await select("Subject b");
    await click(button(/Delete selected/));
    await click(button("Delete"));

    expect(deleteMail).toHaveBeenCalledTimes(1);
    expect(deleteMail).toHaveBeenCalledWith(["a"]);
    expect(host.querySelector(".toast")?.textContent).toContain("Deleting 1 mail…");
    await settle(DELETE_UNDO_MS);
    expect(deleteMail).toHaveBeenCalledTimes(2);
    expect(deleteMail).toHaveBeenLastCalledWith(["b"]);
  });

  it("brings the mails back and shows the error when the delete fails", async () => {
    deleteMail.mockRejectedValue(new ApiError(500, "Boom"));
    await mount();
    await select("Subject a");
    await click(button(/Delete selected/));
    await click(button("Delete"));
    expect(subjects()).toHaveLength(2);

    await settle(DELETE_UNDO_MS);

    expect(subjects()).toHaveLength(3);
    expect(host.querySelector('[role="alert"]')?.textContent).toContain("Boom");
  });

  it("falls back to a general message when the failure has no text of its own", async () => {
    deleteMail.mockRejectedValue(new Error("network"));
    await mount();
    await select("Subject a");
    await click(button(/Delete selected/));
    await click(button("Delete"));

    await settle(DELETE_UNDO_MS);

    expect(host.querySelector('[role="alert"]')?.textContent).toContain("That did not work.");
  });

  it("Delete old counts the listed mails older than the chosen days, hides them and deletes by age", async () => {
    listMail.mockResolvedValue([mail("a", "2026-10-01T10:00:00Z"), mail("b", "2026-11-19T10:00:00Z"), mail("c", "2026-09-01T10:00:00Z")]);
    await mount();
    await act(async () => {
      const input = host.querySelector('input[aria-label="Days"]') as HTMLInputElement;
      input.value = "30";
      input.dispatchEvent(new Event("input", { bubbles: true }));
    });

    await click(button("Delete old"));
    expect(dialog()?.textContent).toContain("Delete mails older than 30 days?");
    expect(dialog()?.textContent).toContain("2 mails in the list are older than 30 days");
    await click(dialog()?.querySelector(".primary") as HTMLElement);

    expect(subjects()).toEqual([expect.stringContaining("Subject b")]);
    expect(host.querySelector(".toast")?.textContent).toContain("Deleting 2 mails older than 30 days…");
    await settle(DELETE_UNDO_MS);

    expect(deleteOldMail).toHaveBeenCalledWith(30);
    expect(deleteMail).not.toHaveBeenCalled();
    expect(host.querySelector(".toast")?.textContent).toContain("Deleted 1 mails older than 30 days.");
  });

  it("Delete old asks with a general text when no listed mail is that old", async () => {
    await mount();

    await click(button("Delete old"));

    expect(dialog()?.textContent).toContain("Every stored mail older than 14 days");
    await click(button("Cancel"));
    expect(dialog()).toBeNull();
  });

  it("Delete old does nothing for a number of days below one", async () => {
    await mount();
    await act(async () => {
      const input = host.querySelector('input[aria-label="Days"]') as HTMLInputElement;
      input.value = "0";
      input.dispatchEvent(new Event("input", { bubbles: true }));
    });

    expect(button("Delete old").disabled).toBe(true);
  });

  it("Select all and Clear selection work on the visible mails only", async () => {
    await mount();
    await select("Subject a");
    await click(button(/Delete selected/));
    await click(button("Delete"));

    await click(button("Select all"));

    expect(button(/Delete selected \(2\)/)).toBeDefined();
    await click(button("Clear selection"));
    expect(button("Delete selected").disabled).toBe(true);
  });
});
