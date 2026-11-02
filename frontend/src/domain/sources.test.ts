import { describe, expect, it } from "vitest";
import type { SourceStatus } from "../api/sources";
import { healthDetail, healthLabel } from "./sources";

const base: SourceStatus = {
  id: "arbeitnow",
  health: "ok",
  lastSuccessAt: null,
  lastFailureAt: null,
  consecutiveFailures: 0,
  circuitOpenUntil: null,
  lastError: null,
  lastRun: null,
};

describe("healthLabel", () => {
  it("uses words, not only colors", () => {
    expect(healthLabel("ok")).toBe("OK");
    expect(healthLabel("warn")).toBe("Warning");
    expect(healthLabel("error")).toBe("Error");
    expect(healthLabel("unknown")).toBe("Not run yet");
  });
});

describe("healthDetail", () => {
  it("says when nothing has run yet", () => {
    expect(healthDetail({ ...base, health: "unknown" })).toBe("No run has reached this source yet.");
  });

  it("counts failures in a row with correct plural", () => {
    expect(healthDetail({ ...base, health: "warn", consecutiveFailures: 1 })).toBe("1 failure in a row.");
    expect(healthDetail({ ...base, health: "warn", consecutiveFailures: 3 })).toBe("3 failures in a row.");
  });

  it("names the retry time when the breaker is open", () => {
    const text = healthDetail({
      ...base,
      health: "error",
      consecutiveFailures: 5,
      circuitOpenUntil: "2026-11-01T16:15:00Z",
    });
    expect(text.startsWith("Paused after 5 failures in a row; retry after ")).toBe(true);
  });

  it("reports success for a healthy source", () => {
    expect(healthDetail(base)).toBe("Last run succeeded.");
  });
});
