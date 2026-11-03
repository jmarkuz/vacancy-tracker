import { describe, expect, it } from "vitest";
import { ACTIVE_STATUSES, barPercent, chartSummary, funnelMax, kpis, quietText, weekLabel } from "./dashboard";
import { parseCriteria } from "./criteria";

const counts = { newToday: 3, inbox: 7, active: 5, interviews: 2, overdue: 1 };
const now = new Date("2026-11-01T10:00:00Z");

describe("kpis", () => {
  const cards = kpis(counts, now);
  const filterOf = (id: string) => parseCriteria(decodeURIComponent(cards.find((c) => c.id === id)!.href.split("filter=")[1]));

  it("carries the numbers in order", () => {
    expect(cards.map((c) => [c.id, c.value])).toEqual([["new-today", 3], ["inbox", 7], ["active", 5], ["interviews", 2], ["overdue", 1]]);
  });

  it("opens the Inbox for the new-vacancy cards", () => {
    expect(cards[0].href).toBe("#/inbox");
    expect(cards[1].href).toBe("#/inbox");
  });

  it("opens the Pipeline filtered to the active statuses", () => {
    expect(filterOf("active")).toEqual([{ field: "status", operator: "in", value: ACTIVE_STATUSES }]);
  });

  it("opens the Pipeline filtered to interviews", () => {
    expect(filterOf("interviews")).toEqual([{ field: "status", operator: "equals", value: "Interview" }]);
  });

  it("opens overdue as active vacancies whose next action is due by now", () => {
    expect(filterOf("overdue")).toEqual([
      { field: "status", operator: "in", value: ACTIVE_STATUSES },
      { field: "nextActionAt", operator: "lte", value: "2026-11-01T10:00:00.000Z" },
    ]);
  });
});

describe("barPercent", () => {
  it("scales to the maximum", () => {
    expect(barPercent(5, 10)).toBe(50);
    expect(barPercent(10, 10)).toBe(100);
  });

  it("keeps a small nonzero value visible and zero empty", () => {
    expect(barPercent(1, 1000)).toBe(2);
    expect(barPercent(0, 10)).toBe(0);
    expect(barPercent(3, 0)).toBe(0);
  });
});

describe("chart helpers", () => {
  it("finds the largest funnel stage, or 0 for none", () => {
    expect(funnelMax([{ status: "a", count: 4 }, { status: "b", count: 9 }])).toBe(9);
    expect(funnelMax([])).toBe(0);
  });

  it("labels a week start without a time zone shift", () => {
    expect(weekLabel("2026-11-02")).toBe("Nov 2");
  });

  it("summarises the bars for screen readers", () => {
    expect(chartSummary([{ start: "2026-10-26", applications: 2 }, { start: "2026-11-02", applications: 0 }]))
      .toBe("Applications per week: Oct 26 2, Nov 2 0");
  });
});

describe("quietText", () => {
  it("counts the days", () => {
    expect(quietText(10)).toBe("Quiet for 10 days");
    expect(quietText(29)).toBe("Quiet for 29 days");
  });

  it("uses the singular for one day", () => {
    expect(quietText(1)).toBe("Quiet for 1 day");
  });

  it("says over a month from thirty days on", () => {
    expect(quietText(30)).toBe("Quiet for over a month");
    expect(quietText(75)).toBe("Quiet for over a month");
  });
});
