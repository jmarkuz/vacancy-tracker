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
  setMailInterval: vi.fn(),
}));

const getStatus = vi.mocked(mailApi.getMailStatus);
const listMail = vi.mocked(mailApi.listMail);
const startSync = vi.mocked(mailApi.startMailSync);
const startReread = vi.mocked(mailApi.startMailReread);
const setInterval_ = vi.mocked(mailApi.setMailInterval);

const result = (patch: Partial<SyncResult> = {}): SyncResult => ({ configured: true, seen: 5, stored: 2, linked: 1, suggested: 1, error: null, ...patch });
const status = (patch: Partial<MailStatus> = {}): MailStatus => ({
  configured: true,
  folders: ["INBOX"],
  pending: 0,
  lastSync: null,
  lastError: null,
  syncing: false,
  lastResult: null,
  intervalMinutes: 15,
  nextCheck: null,
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
    expect(listMail).toHaveBeenLastCalledWith(expect.objectContaining({ pending: true }), expect.anything());
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

describe("MailScreen: how often the mailbox is read", () => {
  const slider = () => host.querySelector("input.slider") as HTMLInputElement;
  const move = async (step: number) => {
    await act(async () => {
      slider().value = String(step);
      slider().dispatchEvent(new Event("input", { bubbles: true }));
    });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(500);
    });
  };

  it("is a native range over the six steps, set to the saved interval, with a readable value and a label", async () => {
    getStatus.mockResolvedValue(status({ intervalMinutes: 30 }));
    await mount();

    expect(slider().type).toBe("range");
    expect([slider().min, slider().max, slider().step]).toEqual(["0", "5", "1"]);
    expect(slider().value).toBe("3");
    expect(slider().getAttribute("aria-valuetext")).toBe("every 30 minutes");
    expect(slider().getAttribute("aria-label")).toBe("How often to read the mailbox");
    expect(host.querySelector(".slider-ticks")?.textContent).toBe("51015304560");
  });

  it("says the real interval in the text and the time of the next check", async () => {
    const next = new Date(2026, 10, 5, 10, 45).toISOString();
    getStatus.mockResolvedValue(status({ intervalMinutes: 30, nextCheck: next, lastSync: new Date(2026, 10, 5, 10, 15).toISOString() }));
    await mount();

    const text = host.querySelector(".sync-text")?.textContent ?? "";
    expect(text).toContain("Reading INBOX every 30 minutes, read-only.");
    expect(text).toMatch(/Next check \d{1,2}:45/);
    expect(text).not.toContain("15 minutes");
  });

  it("names the hour step and leaves out the next check when none is known", async () => {
    getStatus.mockResolvedValue(status({ intervalMinutes: 60 }));
    await mount();

    expect(host.querySelector(".sync-text")?.textContent).toContain("every hour");
    expect(host.querySelector(".sync-text")?.textContent).not.toContain("Next check");
    expect(slider().value).toBe("5");
  });

  it("maps the steps to minutes, saves once after the last move and says it is saved", async () => {
    getStatus.mockResolvedValue(status());
    setInterval_.mockResolvedValue({ intervalMinutes: 5 });
    await mount();

    await act(async () => {
      slider().value = "4";
      slider().dispatchEvent(new Event("input", { bubbles: true }));
    });
    expect(setInterval_).not.toHaveBeenCalled();
    await move(0);

    expect(setInterval_).toHaveBeenCalledTimes(1);
    expect(setInterval_).toHaveBeenCalledWith(5);
    expect(host.querySelector(".toast")?.textContent).toContain("Saved: reading the mailbox every 5 minutes.");
    expect(host.querySelector(".sync-text")?.textContent).toContain("every 5 minutes");
    expect(slider().getAttribute("aria-valuetext")).toBe("every 5 minutes");
  });

  it.each([
    [0, 5],
    [1, 10],
    [2, 15],
    [3, 30],
    [4, 45],
    [5, 60],
  ])("step %i is %i minutes", async (step, minutes) => {
    getStatus.mockResolvedValue(status({ intervalMinutes: step === 2 ? 5 : 15 }));
    setInterval_.mockResolvedValue({ intervalMinutes: minutes });
    await mount();

    await move(step);

    expect(setInterval_).toHaveBeenCalledWith(minutes);
  });

  it("shows the error and goes back to the saved interval when saving fails", async () => {
    getStatus.mockResolvedValue(status({ intervalMinutes: 15 }));
    setInterval_.mockRejectedValue(new ApiError(400, "The interval must be one of [5, 10, 15, 30, 45, 60] minutes."));
    await mount();

    await move(5);

    expect(setInterval_).toHaveBeenCalledWith(60);
    expect(host.querySelector(".sync-interval-error")?.textContent).toContain("must be one of");
    expect(slider().value).toBe("2");
    expect(slider().getAttribute("aria-valuetext")).toBe("every 15 minutes");
    expect(host.querySelector(".toast")).toBeNull();
  });

  it("uses a general message when the failure has no text of its own", async () => {
    getStatus.mockResolvedValue(status());
    setInterval_.mockRejectedValue(new Error("offline"));
    await mount();

    await move(1);

    expect(host.querySelector(".sync-interval-error")?.textContent).toBe("Could not save how often the mailbox is read.");
  });

  it("is not shown without a mailbox", async () => {
    getStatus.mockResolvedValue(status({ configured: false }));
    await mount();

    expect(slider()).toBeNull();
  });
});
