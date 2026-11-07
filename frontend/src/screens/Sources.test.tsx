import { render } from "preact";
import { act } from "preact/test-utils";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import * as client from "../api/client";
import * as runs from "../api/runs";
import * as sourcesApi from "../api/sources";
import type { SourceStatus } from "../api/sources";
import { settle } from "../components/loadFailed.helpers";
import { Sources } from "./Sources";

vi.mock("../api/sources", async (importOriginal) => ({
  ...(await importOriginal<typeof sourcesApi>()),
  listSources: vi.fn(),
  refreshSource: vi.fn(),
}));
vi.mock("../api/runs", async (importOriginal) => ({ ...(await importOriginal<typeof runs>()), openRunStream: vi.fn() }));

const listSources = vi.mocked(sourcesApi.listSources);
const refreshSource = vi.mocked(sourcesApi.refreshSource);
const openRunStream = vi.mocked(runs.openRunStream);

const source = (extra: Partial<SourceStatus> = {}): SourceStatus => ({
  id: "hh",
  health: "ok",
  lastSuccessAt: "2026-11-02T10:00:00Z",
  lastFailureAt: null,
  consecutiveFailures: 0,
  circuitOpenUntil: null,
  lastError: null,
  lastRun: null,
  ...extra,
});

let host: HTMLElement;
let handlers: { onDone: (e: unknown) => void; onError: () => void };
let close: ReturnType<typeof vi.fn>;

beforeEach(() => {
  host = document.createElement("div");
  document.body.appendChild(host);
  close = vi.fn();
  openRunStream.mockImplementation((_id, h) => {
    handlers = h as never;
    return { close } as never;
  });
});
afterEach(() => {
  render(null, host);
  host.remove();
  vi.resetAllMocks();
});

const mount = async (dataVersion = 0) => {
  await act(async () => {
    render(<Sources dataVersion={dataVersion} />, host);
  });
  await settle(4);
};

const refreshButton = () => host.querySelector<HTMLButtonElement>("tbody button")!;

describe("Sources", () => {
  it("lists each source with its health, last success and last run", async () => {
    listSources.mockResolvedValue([
      source({ lastRun: { fetched: 5, newCount: 2, updated: 1, duplicates: 0, errors: 1, durationMs: 10, errorMessage: null } }),
      source({ id: "linkedin", health: "error", lastSuccessAt: null, lastError: "Blocked", lastRun: null }),
    ]);
    await mount();

    expect(host.textContent).toContain("+2 new, 1 updated, 1 errors");
    expect(host.textContent).toContain("Last error: Blocked");
    expect(host.querySelectorAll("tbody tr")).toHaveLength(2);
  });

  it("says it could not load the sources and retries", async () => {
    listSources.mockRejectedValueOnce(new client.ApiError(503, "Busy"));
    await mount();
    expect(host.querySelector('[role="alert"]')?.textContent).toContain("Busy");

    listSources.mockResolvedValue([source()]);
    await act(async () => {
      host.querySelector<HTMLButtonElement>('[role="alert"] button')!.click();
    });
    await settle(4);
    expect(host.querySelectorAll("tbody tr")).toHaveLength(1);
  });

  it("uses a generic message for any other load failure", async () => {
    listSources.mockRejectedValue(new Error("offline"));
    await mount();
    expect(host.querySelector('[role="alert"]')?.textContent).toContain("Could not load sources.");
  });

  it("refreshes one source, follows its run and reports the outcome", async () => {
    listSources.mockResolvedValue([source()]);
    refreshSource.mockResolvedValue({ runId: "r1", started: true });
    await mount();

    await act(async () => {
      refreshButton().click();
    });
    await settle(3);
    expect(refreshButton().textContent).toBe("Refreshing…");
    expect(openRunStream).toHaveBeenCalledWith("r1", expect.anything());

    await act(async () => {
      handlers.onDone({ status: "completed", new: 3, updated: 0, failedSources: 0 });
    });
    await settle(3);

    expect(host.textContent).toContain("hh: +3 new, 0 updated");
    expect(refreshButton().textContent).toBe("Refresh this source");
  });

  it("marks a failed source and an interrupted run as errors", async () => {
    listSources.mockResolvedValue([source()]);
    refreshSource.mockResolvedValue({ runId: "r1", started: true });
    await mount();
    await act(async () => {
      refreshButton().click();
    });
    await settle(3);

    await act(async () => {
      handlers.onDone({ status: "completed", new: 0, updated: 0, failedSources: 2 });
    });
    await settle(3);
    expect(host.textContent).toContain("2 sources failed");

    refreshSource.mockResolvedValue({ runId: "r2", started: true });
    await act(async () => {
      refreshButton().click();
    });
    await settle(3);
    await act(async () => {
      handlers.onDone({ status: "interrupted", new: 0, updated: 0, failedSources: 0 });
    });
    await settle(3);
    expect(host.textContent).toContain("Refresh of hh was interrupted.");
  });

  it("tells the user when a refresh was already running", async () => {
    listSources.mockResolvedValue([source()]);
    refreshSource.mockResolvedValue({ runId: "r1", started: false });
    await mount();

    await act(async () => {
      refreshButton().click();
    });
    await settle(3);

    expect(host.textContent).toContain("A refresh is already running.");
  });

  it("reports a lost connection and a refresh that could not start", async () => {
    listSources.mockResolvedValue([source()]);
    refreshSource.mockResolvedValue({ runId: "r1", started: true });
    await mount();
    await act(async () => {
      refreshButton().click();
    });
    await settle(3);
    await act(async () => {
      handlers.onError();
    });
    await settle(3);
    expect(host.textContent).toContain("Lost the connection while refreshing.");

    refreshSource.mockRejectedValue(new Error("Source is disabled"));
    await act(async () => {
      refreshButton().click();
    });
    await settle(3);
    expect(host.textContent).toContain("Source is disabled");
  });

  it("closes the run stream when the screen is left", async () => {
    listSources.mockResolvedValue([source()]);
    refreshSource.mockResolvedValue({ runId: "r1", started: true });
    await mount();
    await act(async () => {
      refreshButton().click();
    });
    await settle(3);

    render(null, host);
    expect(close).toHaveBeenCalled();
  });
});
