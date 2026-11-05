import { render } from "preact";
import { act } from "preact/test-utils";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "../api/client";
import * as mailApi from "../api/mail";
import type { MailStatus, SyncResult } from "../api/mail";
import { SYNC_POLL_MS } from "../domain/mail";
import { MailScreen } from "./MailScreen";

vi.mock("../api/mail", async (importOriginal) => ({
  ...(await importOriginal<typeof mailApi>()),
  getMailStatus: vi.fn(),
  listMail: vi.fn(),
  startMailSync: vi.fn(),
  startMailReread: vi.fn(),
}));

const getStatus = vi.mocked(mailApi.getMailStatus);
const listMail = vi.mocked(mailApi.listMail);
const startSync = vi.mocked(mailApi.startMailSync);
const startReread = vi.mocked(mailApi.startMailReread);

const result = (patch: Partial<SyncResult> = {}): SyncResult => ({ configured: true, seen: 5, stored: 2, linked: 1, suggested: 1, error: null, ...patch });
const status = (patch: Partial<MailStatus> = {}): MailStatus => ({
  configured: true,
  folders: ["INBOX"],
  pending: 0,
  lastSync: null,
  lastError: null,
  syncing: false,
  lastResult: null,
  ...patch,
});

let host: HTMLElement;

async function mount() {
  await act(async () => {
    render(<MailScreen />, host);
  });
  await act(async () => {
    await vi.advanceTimersByTimeAsync(0);
  });
}

const checkButton = () => [...host.querySelectorAll("button")].find((b) => /Check mail now|Checking/.test(b.textContent ?? "")) as HTMLButtonElement;

beforeEach(() => {
  vi.useFakeTimers();
  host = document.createElement("div");
  document.body.appendChild(host);
  listMail.mockResolvedValue([]);
  startSync.mockResolvedValue({ started: true, reason: null });
});

afterEach(() => {
  render(null, host);
  host.remove();
  vi.useRealTimers();
  vi.resetAllMocks();
});

describe("MailScreen: Check mail now runs in the background", () => {
  it("is disabled when no mailbox is set up", async () => {
    getStatus.mockResolvedValue(status({ configured: false }));

    await mount();

    expect(checkButton().disabled).toBe(true);
    expect(host.textContent).toContain("The mailbox is not connected");
  });

  it("starts a read, shows that it is checking, and reports what it found when it is done", async () => {
    getStatus.mockResolvedValueOnce(status()); // opening the screen
    await mount();
    expect(checkButton().disabled).toBe(false);

    getStatus.mockResolvedValueOnce(status({ syncing: true })); // right after the start
    await act(async () => {
      checkButton().click();
    });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });
    expect(startSync).toHaveBeenCalledTimes(1);
    expect(checkButton().textContent).toContain("Checking…");
    expect(checkButton().disabled).toBe(true);

    getStatus.mockResolvedValueOnce(status({ syncing: true })); // still running at the first poll
    await act(async () => {
      await vi.advanceTimersByTimeAsync(SYNC_POLL_MS);
    });
    expect(host.textContent).not.toContain("Read 5 mails");

    const callsBefore = listMail.mock.calls.length;
    getStatus.mockResolvedValue(status({ syncing: false, lastResult: result() })); // done
    await act(async () => {
      await vi.advanceTimersByTimeAsync(SYNC_POLL_MS);
    });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });

    expect(host.textContent).toContain("Read 5 mails: 2 about jobs, 1 linked, 1 suggestion.");
    expect(checkButton().textContent).toContain("Check mail now");
    expect(listMail.mock.calls.length).toBeGreaterThan(callsBefore);
  });

  it("follows a read that is already running instead of reporting a conflict", async () => {
    getStatus.mockResolvedValueOnce(status());
    await mount();
    startSync.mockRejectedValueOnce(new ApiError(409, "A read is already running"));
    getStatus.mockResolvedValueOnce(status({ syncing: true }));

    await act(async () => {
      checkButton().click();
    });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });

    expect(host.querySelector('[role="alert"]')).toBeNull();
    expect(checkButton().textContent).toContain("Checking…");
  });

  it("shows the mailbox error of a finished read", async () => {
    getStatus.mockResolvedValueOnce(status({ syncing: true }));
    await mount();
    getStatus.mockResolvedValue(status({ syncing: false, lastResult: result({ error: "Login failed" }) }));

    await act(async () => {
      await vi.advanceTimersByTimeAsync(SYNC_POLL_MS);
    });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });

    expect(host.querySelector('[role="alert"]')?.textContent).toContain("Login failed");
  });

  it("shows an error when the start itself fails", async () => {
    getStatus.mockResolvedValue(status());
    await mount();
    startSync.mockRejectedValueOnce(new ApiError(500, "Server error"));

    await act(async () => {
      checkButton().click();
    });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });

    expect(host.querySelector('[role="alert"]')?.textContent).toContain("Server error");
  });
});

describe("MailScreen: the state of the mailbox", () => {
  it("shows a quiet card when idle and a live one, with the arrows turning, while it reads", async () => {
    getStatus.mockResolvedValue(status({ lastSync: "2026-11-05T08:16:00Z" }));
    await mount();

    const card = () => host.querySelector(".sync-card") as HTMLElement;
    expect(card().textContent).toContain("Mailbox connected");
    expect(card().classList.contains("is-syncing")).toBe(false);
    expect(host.querySelector(".sync-bar")).toBeNull();
    expect(checkButton().classList.contains("is-refreshing")).toBe(false);

    getStatus.mockResolvedValue(status({ syncing: true }));
    await act(async () => {
      checkButton().click();
    });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });

    expect(card().classList.contains("is-syncing")).toBe(true);
    expect(card().textContent).toContain("Checking the mailbox…");
    expect(card().querySelector(".sync-bar")).not.toBeNull();
    expect(card().querySelector(".icon-spin")).not.toBeNull();
    expect(checkButton().classList.contains("is-refreshing")).toBe(true);
    expect(checkButton().querySelector(".icon-spin")).not.toBeNull();
  });

  it("filters to the suggestions with a toggle button", async () => {
    getStatus.mockResolvedValue(status({ pending: 2 }));
    await mount();
    const toggle = () => [...host.querySelectorAll("button")].find((b) => b.textContent?.trim() === "Only suggestions") as HTMLButtonElement;

    expect(toggle().getAttribute("aria-pressed")).toBe("false");
    expect(host.textContent).toContain("2 to decide");
    await act(async () => {
      toggle().click();
    });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });

    expect(toggle().getAttribute("aria-pressed")).toBe("true");
    expect(listMail).toHaveBeenLastCalledWith({ pending: true }, expect.anything());
  });
});

describe("MailScreen: Re-read mail", () => {
  const rereadButton = () => [...host.querySelectorAll("button")].find((b) => b.textContent?.trim() === "Re-read mail") as HTMLButtonElement;
  const click = async (button: HTMLButtonElement) => {
    await act(async () => {
      button.click();
    });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });
  };

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("asks first, and does nothing when the owner says no", async () => {
    getStatus.mockResolvedValue(status());
    await mount();
    const confirm = vi.fn(() => false);
    vi.stubGlobal("confirm", confirm);

    await click(rereadButton());

    expect(confirm).toHaveBeenCalledTimes(1);
    expect(String(confirm.mock.calls[0])).toContain("mails you deleted come back");
    expect(startReread).not.toHaveBeenCalled();
    expect(startSync).not.toHaveBeenCalled();
  });

  it("starts a re-read and follows it like a check, when the owner agrees", async () => {
    getStatus.mockResolvedValueOnce(status());
    await mount();
    vi.stubGlobal("confirm", vi.fn(() => true));
    startReread.mockResolvedValue({ started: true, reason: null });
    getStatus.mockResolvedValue(status({ syncing: true }));

    await click(rereadButton());

    expect(startReread).toHaveBeenCalledTimes(1);
    expect(startSync).not.toHaveBeenCalled();
    expect(checkButton().textContent).toContain("Checking…");
    expect(rereadButton().disabled).toBe(true);
    expect(host.querySelector(".sync-card.is-syncing")).not.toBeNull();
  });

  it("follows a read that is already running instead of reporting a conflict", async () => {
    getStatus.mockResolvedValueOnce(status());
    await mount();
    vi.stubGlobal("confirm", vi.fn(() => true));
    startReread.mockRejectedValueOnce(new ApiError(409, "A read is already running"));
    getStatus.mockResolvedValue(status({ syncing: true }));

    await click(rereadButton());

    expect(host.querySelector('[role="alert"]')).toBeNull();
    expect(checkButton().textContent).toContain("Checking…");
  });

  it("shows the error when it cannot start", async () => {
    getStatus.mockResolvedValue(status());
    await mount();
    vi.stubGlobal("confirm", vi.fn(() => true));
    startReread.mockRejectedValueOnce(new ApiError(500, "Boom"));

    await click(rereadButton());

    expect(host.querySelector('[role="alert"]')?.textContent).toContain("Boom");
  });

  it("is disabled when no mailbox is set up", async () => {
    getStatus.mockResolvedValue(status({ configured: false }));
    await mount();

    expect(rereadButton().disabled).toBe(true);
  });

  it("has the same look as the other buttons: a secondary pill with an icon", async () => {
    getStatus.mockResolvedValue(status());
    await mount();

    expect(rereadButton().className).toBe("btn");
    expect(rereadButton().querySelector('svg.icon[data-icon="history"]')).not.toBeNull();
  });
});
