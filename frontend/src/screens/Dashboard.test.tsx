import { render } from "preact";
import { act } from "preact/test-utils";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import * as dashboardApi from "../api/dashboard";
import * as sourcesApi from "../api/sources";
import type { Dashboard as Data } from "../api/dashboard";
import type { SourceStatus } from "../api/sources";
import { settle } from "../components/loadFailed.helpers";
import { Dashboard } from "./Dashboard";

vi.mock("../api/dashboard", async (importOriginal) => ({ ...(await importOriginal<typeof dashboardApi>()), getDashboard: vi.fn() }));
vi.mock("../api/sources", async (importOriginal) => ({ ...(await importOriginal<typeof sourcesApi>()), listSources: vi.fn() }));
const getDashboard = vi.mocked(dashboardApi.getDashboard);
const listSources = vi.mocked(sourcesApi.listSources);

const data: Data = {
  newToday: 3,
  inbox: 9,
  active: 2,
  interviews: 1,
  overdue: 0,
  topMatches: [],
  funnel: [],
  applicationsPerWeek: [],
  nextActions: [],
};

const source = (id: string): SourceStatus => ({
  id,
  health: "ok",
  lastSuccessAt: null,
  lastFailureAt: null,
  consecutiveFailures: 0,
  circuitOpenUntil: null,
  lastError: null,
  lastRun: null,
});

let host: HTMLElement;

beforeEach(() => {
  host = document.createElement("div");
  document.body.appendChild(host);
  getDashboard.mockResolvedValue(data);
});

afterEach(() => {
  render(null, host);
  host.remove();
  vi.resetAllMocks();
});

const show = async () => {
  await act(async () => {
    render(<Dashboard />, host);
  });
  await settle(4);
};

describe("Dashboard: source health", () => {
  it("lists the sources", async () => {
    listSources.mockResolvedValue([source("djinni"), source("arbeitnow")]);

    await show();

    const table = host.querySelector('table[aria-label="Source health"]');
    expect(table?.textContent).toContain("djinni");
    expect(table?.textContent).toContain("arbeitnow");
  });

  it("says there is no information yet when the list is empty", async () => {
    listSources.mockResolvedValue([]);

    await show();

    expect(host.textContent).toContain("No source information yet.");
    expect(host.querySelector('[role="alert"]')).toBeNull();
  });

  it("says it could not load the health, instead of 'no information', and loads again on Try again", async () => {
    listSources.mockRejectedValueOnce(new Error("offline"));
    await show();
    expect(host.querySelector('[role="alert"]')?.textContent).toContain("Could not load the source health.");
    expect(host.textContent).not.toContain("No source information yet.");

    listSources.mockResolvedValueOnce([source("djinni")]);
    await act(async () => {
      (host.querySelector('[role="alert"] button') as HTMLButtonElement).click();
    });
    await settle(4);

    expect(host.querySelector('[role="alert"]')).toBeNull();
    expect(host.querySelector('table[aria-label="Source health"]')?.textContent).toContain("djinni");
  });

  it("keeps the rest of the dashboard when only the source health fails", async () => {
    listSources.mockRejectedValue(new Error("offline"));

    await show();

    expect(host.textContent).toContain("Top matches");
    expect(host.textContent).toContain("Applications per week");
  });
});
