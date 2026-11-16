import { describe, expect, it } from "vitest";
import { BOARD_COLUMNS } from "../domain/kanban";
import { STATUS_TRANSITIONS } from "../domain/statuses";

const css = import.meta.glob("./*.css", { query: "?raw", import: "default", eager: true }) as Record<string, string>;
const all = Object.values(css).join("\n");

/** The statuses of the backend's VacancyStatus enum, by label. Change this list when that enum changes. */
const BACKEND_STATUSES = [
  "New", "Saved", "Applied", "Screening", "Interview", "Test task", "Offer", "Accepted", "Rejected", "Withdrawn", "Expired",
  "Dismissed", "Hidden",
];
const slug = (s: string) => s.toLowerCase().replace(/\s+/g, "-");

describe("every status of the backend", () => {
  it("is in the transition table, so bulk change and the board know where it can go", () => {
    expect(Object.keys(STATUS_TRANSITIONS).sort()).toEqual([...BACKEND_STATUSES].sort());
  });

  it("has a badge colour rule and a colour token, so a row never shows a bare, unstyled status", () => {
    for (const status of BACKEND_STATUSES) {
      expect(all, `.status-${slug(status)}`).toMatch(new RegExp(`\\.status-${slug(status)}\\b`));
      expect(all, `--status-${slug(status)}`).toContain(`--status-${slug(status)}:`);
    }
  });

  it("is on the board or in the Inbox's group, never in neither", () => {
    const inbox = ["New", "Hidden", "Dismissed", "Expired"];
    for (const status of BACKEND_STATUSES) {
      expect(BOARD_COLUMNS.includes(status) || inbox.includes(status), status).toBe(true);
    }
    expect(BOARD_COLUMNS.filter((s) => inbox.includes(s))).toEqual([]);
  });
});
