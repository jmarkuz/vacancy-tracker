import { describe, expect, it } from "vitest";
import type { SyncResult } from "../api/mail";
import { syncSummary } from "./mail";

const result = (patch: Partial<SyncResult>): SyncResult => ({ configured: true, seen: 0, stored: 0, linked: 0, suggested: 0, error: null, ...patch });

describe("syncSummary", () => {
  it("counts what the read found", () => {
    expect(syncSummary(result({ seen: 12, stored: 3, linked: 2, suggested: 2 }))).toBe("Read 12 mails: 3 about jobs, 2 linked, 2 suggestions.");
  });

  it("uses the singular for one mail and one suggestion", () => {
    expect(syncSummary(result({ seen: 1, stored: 1, linked: 1, suggested: 1 }))).toBe("Read 1 mail: 1 about jobs, 1 linked, 1 suggestion.");
  });

  it("says so when nothing was found", () => {
    expect(syncSummary(result({}))).toBe("Read 0 mails: 0 about jobs, 0 linked, 0 suggestions.");
  });
});
