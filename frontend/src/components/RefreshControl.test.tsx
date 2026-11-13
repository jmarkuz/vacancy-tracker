import { render } from "preact";
import { act } from "preact/test-utils";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { RunStream } from "../api/runs";

type Handlers = Parameters<typeof import("../api/runs").openRunStream>[1];
let handlers: Handlers;
const startRun = vi.fn();
const close = vi.fn();

vi.mock("../api/runs", () => ({
  getLatestRun: vi.fn(async () => ({
    runId: "r0", status: "completed", finishedAt: "2026-11-04T08:00:00Z", newCount: 0, updated: 0, failedSources: 0,
    nextScheduledAt: "2026-11-04T20:00:00Z",
  })),
  startRun: (...a: unknown[]) => startRun(...a),
  openRunStream: (_id: string, h: Handlers): RunStream => {
    handlers = h;
    return { close } as unknown as RunStream;
  },
}));

import { RefreshControl } from "./RefreshControl";

let host: HTMLElement;

const flush = async () => {
  for (let i = 0; i < 3; i++) {
    await act(async () => {
      await Promise.resolve();
    });
  }
};

const button = () => host.querySelector("button.btn.primary") as HTMLButtonElement;
const popup = () => host.querySelector(".progress-pop");

beforeEach(async () => {
  startRun.mockReset();
  startRun.mockResolvedValue({ runId: "r1", started: true, status: "running" });
  host = document.createElement("div");
  document.body.appendChild(host);
  await act(async () => {
    render(<RefreshControl />, host);
  });
  await flush();
});

afterEach(() => {
  render(null, host);
  host.remove();
});

const start = async () => {
  await act(async () => {
    button().click();
  });
  await flush();
};

describe("RefreshControl: progress on the button, the list on hover", () => {
  it("shows no popup before a refresh and no spinning arrows", () => {
    expect(popup()).toBeNull();
    expect(button().textContent?.trim()).toBe("Refresh");
    expect(button().querySelector(".icon-spin")).toBeNull();
    expect(button().classList.contains("is-refreshing")).toBe(false);
  });

  it("animates the button while it runs, and keeps it reachable by the pointer (not disabled)", async () => {
    await start();

    expect(button().textContent?.trim()).toBe("Refreshing…");
    expect(button().classList.contains("is-refreshing")).toBe(true);
    expect(button().querySelector(".icon-spin")).not.toBeNull();
    expect(button().disabled).toBe(false);
    expect(button().getAttribute("aria-disabled")).toBe("true");
    expect(button().getAttribute("aria-busy")).toBe("true");
  });

  it("keeps the source list inside a hover/focus wrapper so the styles can show it only then", async () => {
    await start();
    await act(async () => {
      handlers.onSource({ adapter: "adzuna", state: "finished", new: 0 });
      handlers.onSource({ adapter: "arbeitnow", state: "running" });
    });

    const trigger = host.querySelector(".refresh-trigger");
    expect(trigger?.contains(button())).toBe(true);
    expect(trigger?.contains(popup())).toBe(true);
    expect(popup()?.textContent).toContain("adzuna");
    expect(popup()?.textContent).toContain("running");
    expect(button().getAttribute("aria-describedby")).toBe(popup()?.id);
  });

  it("ignores a second click while running and goes back to normal when the run is done", async () => {
    await start();
    await act(async () => {
      button().click();
    });
    expect(startRun).toHaveBeenCalledTimes(1);

    await act(async () => {
      handlers.onDone({ status: "completed", new: 3, updated: 0, failedSources: 0 });
    });
    await flush();

    expect(popup()).toBeNull();
    expect(button().classList.contains("is-refreshing")).toBe(false);
    expect(button().getAttribute("aria-disabled")).toBe("false");
    expect(host.textContent).toContain("Open Inbox");
  });

  it("reports a lost connection and a failed start", async () => {
    await start();
    await act(async () => {
      handlers.onError();
    });
    expect(host.textContent).toContain("Lost the connection");
    expect(button().classList.contains("is-refreshing")).toBe(false);

    startRun.mockRejectedValueOnce(new Error("Server is down"));
    await start();
    expect(host.textContent).toContain("Server is down");
  });
});
