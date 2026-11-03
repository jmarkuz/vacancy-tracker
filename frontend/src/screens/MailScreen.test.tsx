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
}));

const getStatus = vi.mocked(mailApi.getMailStatus);
const listMail = vi.mocked(mailApi.listMail);
const startSync = vi.mocked(mailApi.startMailSync);

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
