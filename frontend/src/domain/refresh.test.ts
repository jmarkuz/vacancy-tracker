import { describe, expect, it } from "vitest";
import { refreshedLine, summaryText } from "./refresh";

describe("summaryText", () => {
  it("reads like the design: new, updated, failed sources", () => {
    expect(summaryText({ new: 12, updated: 3, failedSources: 1 })).toBe("+12 new, 3 updated, 1 source failed");
  });

  it("omits the failure clause when no source failed, and pluralizes", () => {
    expect(summaryText({ new: 0, updated: 0, failedSources: 0 })).toBe("+0 new, 0 updated");
    expect(summaryText({ new: 1, updated: 0, failedSources: 2 })).toBe("+1 new, 0 updated, 2 sources failed");
  });
});

/** Local clock time, so the expectations hold in any timezone. */
const hm = (iso: string) => new Intl.DateTimeFormat("en-GB", { hour: "2-digit", minute: "2-digit" }).format(new Date(iso));

describe("refreshedLine", () => {
  it("shows the last finished time and the next scheduled time", () => {
    const line = refreshedLine("2026-11-01T12:02:00Z", "2026-11-01T16:00:00Z", "en-GB");
    expect(line).toBe(`Refreshed ${hm("2026-11-01T12:02:00Z")} · next ${hm("2026-11-01T16:00:00Z")}`);
  });

  it("says so when no run has finished yet", () => {
    expect(refreshedLine(null, "2026-11-01T16:00:00Z", "en-GB")).toBe(`Not refreshed yet · next ${hm("2026-11-01T16:00:00Z")}`);
  });
});
