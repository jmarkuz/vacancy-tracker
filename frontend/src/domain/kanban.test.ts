import { describe, expect, it } from "vitest";
import type { Vacancy } from "../api/types";
import { BOARD_COLUMNS, canMove, groupByStatus, moveMessage, neighbourColumn, withStatus } from "./kanban";
import { STATUS_TRANSITIONS } from "./statuses";

const v = (id: string, status: string) => ({ id, title: id, status }) as Vacancy;

describe("canMove", () => {
  it("follows the transition table", () => {
    expect(canMove("Applied", "Screening")).toBe(true);
    expect(canMove("Applied", "Interview")).toBe(false);
    expect(canMove("Accepted", "Offer")).toBe(false);
  });

  it("allows moving along the board from each column to the next where the table allows it", () => {
    const steps = BOARD_COLUMNS.slice(0, -1).filter((c, i) => canMove(c, BOARD_COLUMNS[i + 1]));
    expect(steps).toEqual(["Saved", "Applied", "Screening", "Interview", "Test task", "Offer"]);
  });

  it("only uses statuses the table knows", () => {
    for (const c of BOARD_COLUMNS) {
      expect(STATUS_TRANSITIONS).toHaveProperty([c]);
    }
  });
});

describe("groupByStatus", () => {
  it("groups by column in board order and drops statuses without one", () => {
    const g = groupByStatus([v("a", "Applied"), v("b", "Saved"), v("c", "Applied"), v("d", "Hidden")]);
    expect(Object.keys(g)).toEqual(BOARD_COLUMNS);
    expect(g.Applied.map((x) => x.id)).toEqual(["a", "c"]);
    expect(g.Saved.map((x) => x.id)).toEqual(["b"]);
    expect(Object.values(g).flat()).toHaveLength(3);
  });
});

describe("neighbourColumn", () => {
  it("steps right and left", () => {
    expect(neighbourColumn("Applied", 1)).toBe("Screening");
    expect(neighbourColumn("Applied", -1)).toBe("Saved");
  });

  it("is null at the edges and for a status that is not on the board", () => {
    expect(neighbourColumn("Saved", -1)).toBeNull();
    expect(neighbourColumn("Withdrawn", 1)).toBeNull();
    expect(neighbourColumn("Hidden", 1)).toBeNull();
  });
});

describe("moveMessage", () => {
  it("confirms an allowed move", () => {
    expect(moveMessage("Dev", "Applied", "Screening")).toEqual({ ok: true, text: 'Moved "Dev" to Screening.' });
  });

  it("refuses a move the table does not allow, naming both statuses", () => {
    expect(moveMessage("Dev", "Applied", "Saved")).toEqual({ ok: false, text: '"Dev" cannot move from Applied to Saved.' });
  });

  it("explains an edge", () => {
    expect(moveMessage("Dev", "Saved", null).text).toMatch(/first column/);
    expect(moveMessage("Dev", "Withdrawn", null).text).toMatch(/last column/);
  });
});

describe("withStatus", () => {
  it("changes one vacancy without touching the others or the input", () => {
    const items = [v("a", "Saved"), v("b", "Saved")];
    const next = withStatus(items, "a", "Applied");
    expect(next.map((x) => x.status)).toEqual(["Applied", "Saved"]);
    expect(items[0].status).toBe("Saved");
  });
});
