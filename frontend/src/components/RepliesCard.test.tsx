import { render } from "preact";
import { act } from "preact/test-utils";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import * as dashboardApi from "../api/dashboard";
import type { Replies } from "../api/dashboard";
import { Dashboard } from "../screens/Dashboard";
import * as sourcesApi from "../api/sources";
import { settle } from "./loadFailed.helpers";
import { RepliesCard } from "./RepliesCard";

vi.mock("../api/dashboard", async (importOriginal) => ({ ...(await importOriginal<typeof dashboardApi>()), getReplies: vi.fn(), getDashboard: vi.fn() }));
vi.mock("../api/sources", async (importOriginal) => ({ ...(await importOriginal<typeof sourcesApi>()), listSources: vi.fn() }));
const getReplies = vi.mocked(dashboardApi.getReplies);

const none = { interview: 0, testTask: 0, offer: 0, rejection: 0, other: 0 };
const sample = (over: Partial<Replies> = {}): Replies => ({
  days: 30,
  applied: 12,
  replied: 5,
  medianDays: 3.5,
  outcomes: { interview: 2, testTask: 1, offer: 0, rejection: 3, other: 4 },
  ...over,
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
    render(<RepliesCard />, host);
  });
  await settle(4);
};

describe("RepliesCard", () => {
  it("says it is loading until the numbers arrive", async () => {
    getReplies.mockReturnValue(new Promise(() => {}));

    await show();

    expect(host.querySelector('[role="status"]')?.textContent).toContain("Loading the replies");
  });

  it("shows applied, replied with its percent, the median wait and the outcomes in words", async () => {
    getReplies.mockResolvedValue(sample());

    await show();

    expect(host.querySelector('[data-stat="applied"]')?.textContent).toBe("12");
    expect(host.querySelector('[data-stat="replied"]')?.textContent).toContain("5");
    expect(host.querySelector('[data-stat="replied"]')?.textContent).toContain("(42%)");
    expect(host.querySelector('[data-stat="median"]')?.textContent).toBe("3.5 days");
    const rows = [...host.querySelectorAll("table[aria-label='Mails by outcome'] tbody tr")].map((r) => r.textContent);
    expect(rows).toEqual(["Interview2", "Test task1", "Offer0", "Rejection3", "Other4"]);
    expect(host.textContent).not.toContain("NaN");
    expect(getReplies).toHaveBeenCalledWith(30, expect.anything());
  });

  it("uses the Mail screen's colour roles for the outcome words", async () => {
    getReplies.mockResolvedValue(sample());

    await show();

    expect(host.querySelector('[data-outcome="rejection"] .mail-category')?.className).toContain("cat-rejection");
    expect(host.querySelector('[data-outcome="interview"] .mail-category')?.className).toContain("cat-interview");
  });

  it("has an empty state when nothing was applied in the period", async () => {
    getReplies.mockResolvedValue(sample({ applied: 0, replied: 0, medianDays: null, outcomes: none }));

    await show();

    expect(host.textContent).toContain("No applications in this period yet.");
    expect(host.querySelector("table")).toBeNull();
    expect(host.textContent).not.toContain("NaN");
  });

  it("has a no-replies state that still shows how many were sent", async () => {
    getReplies.mockResolvedValue(sample({ applied: 4, replied: 0, medianDays: null, outcomes: none }));

    await show();

    expect(host.querySelector('[data-stat="applied"]')?.textContent).toBe("4");
    expect(host.querySelector('[data-stat="replied"]')?.textContent).toContain("(0%)");
    expect(host.querySelector('[data-stat="median"]')?.getAttribute("aria-label")).toBe("No replies yet");
    expect(host.querySelector('[data-stat="median"]')?.textContent).toBe("–");
    expect(host.textContent).toContain("No replies yet. None of these applications has a linked mail.");
    expect(host.querySelector("table")).toBeNull();
  });

  it("shows the failure with Try again and recovers", async () => {
    getReplies.mockRejectedValueOnce(new Error("offline"));
    await show();
    expect(host.querySelector('[role="alert"]')?.textContent).toContain("Could not load the replies.");

    getReplies.mockResolvedValueOnce(sample());
    await act(async () => {
      (host.querySelector('[role="alert"] button') as HTMLButtonElement).click();
    });
    await settle(4);

    expect(host.querySelector('[role="alert"]')).toBeNull();
    expect(host.querySelector('[data-stat="applied"]')?.textContent).toBe("12");
  });

  it("switches to 90 days and asks for that period", async () => {
    getReplies.mockImplementation(async (days) => sample({ days, applied: days === 90 ? 40 : 12 }));
    await show();

    const buttons = [...host.querySelectorAll<HTMLButtonElement>(".replies-periods button")];
    expect(buttons.map((b) => b.getAttribute("aria-pressed"))).toEqual(["true", "false"]);
    await act(async () => {
      buttons[1].click();
    });
    await settle(4);

    expect(getReplies).toHaveBeenLastCalledWith(90, expect.anything());
    expect(host.querySelector('[data-stat="applied"]')?.textContent).toBe("40");
    expect(host.textContent).toContain("the last 90 days");
  });
});

describe("Dashboard with the Replies card", () => {
  it("keeps the dashboard when only the replies fail", async () => {
    vi.mocked(dashboardApi.getDashboard).mockResolvedValue({
      newToday: 0, inbox: 0, active: 0, interviews: 0, overdue: 0, topMatches: [], funnel: [], applicationsPerWeek: [], nextActions: [], followUps: [],
    });
    vi.mocked(sourcesApi.listSources).mockResolvedValue([]);
    getReplies.mockRejectedValue(new Error("offline"));

    await act(async () => {
      render(<Dashboard />, host);
    });
    await settle(4);

    expect(host.textContent).toContain("Top matches");
    expect(host.textContent).toContain("Could not load the replies.");
    expect(host.textContent).not.toContain("Could not load the dashboard.");
  });
});
