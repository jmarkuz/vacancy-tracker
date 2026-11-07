import { render } from "preact";
import { act } from "preact/test-utils";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { Vacancy } from "../api/types";
import { canMove, neighbourColumn } from "../domain/kanban";
import { allowedNext } from "../domain/statuses";
import { settle } from "./loadFailed.helpers";
import { Board } from "./Board";

const vacancy = (id: string, status: string, extra: Partial<Vacancy> = {}): Vacancy =>
  ({ id, title: `Role ${id}`, company: "Acme", status, ...extra }) as Vacancy;

let host: HTMLElement;

beforeEach(() => {
  host = document.createElement("div");
  document.body.appendChild(host);
});

afterEach(() => {
  render(null, host);
  host.remove();
  vi.restoreAllMocks();
});

const mount = async (items: Vacancy[], onMove = vi.fn().mockResolvedValue(undefined), onOpen = vi.fn()) => {
  await act(async () => {
    render(<Board items={items} onMove={onMove} onOpen={onOpen} />, host);
  });
  await settle(2);
  return { onMove, onOpen };
};

const card = (id: string) => host.querySelector<HTMLElement>(`[data-card="${id}"]`)!;

describe("Board", () => {
  it("puts each vacancy in the column of its status and counts them", async () => {
    await mount([vacancy("a", "Saved"), vacancy("b", "Saved"), vacancy("c", "Offer")]);

    expect(host.querySelector('[data-column="Saved"] h3')?.textContent).toContain("2");
    expect(host.querySelector('[data-column="Offer"]')?.textContent).toContain("Role c");
    expect(card("a")).not.toBeNull();
  });

  it("opens a vacancy from its title", async () => {
    const { onOpen } = await mount([vacancy("a", "Saved")]);

    await act(async () => {
      host.querySelector<HTMLButtonElement>(".row-open")!.click();
    });

    expect(onOpen).toHaveBeenCalledWith("a");
  });

  it("moves a card to a neighbouring column with Alt+Right", async () => {
    const target = neighbourColumn("Saved", 1)!;
    const { onMove } = await mount([vacancy("a", "Saved")]);

    await act(async () => {
      card("a").dispatchEvent(new KeyboardEvent("keydown", { key: "ArrowRight", altKey: true, bubbles: true }));
    });
    await settle(3);

    expect(onMove).toHaveBeenCalledWith(expect.objectContaining({ id: "a" }), target);
  });

  it("does nothing on plain arrow keys and refuses to move past the last column", async () => {
    const { onMove } = await mount([vacancy("a", "Rejected")]);

    await act(async () => {
      card("a").dispatchEvent(new KeyboardEvent("keydown", { key: "ArrowRight", bubbles: true }));
    });
    expect(onMove).not.toHaveBeenCalled();

    await act(async () => {
      card("a").dispatchEvent(new KeyboardEvent("keydown", { key: "ArrowRight", altKey: true, bubbles: true }));
    });
    await settle(2);
    expect(onMove).not.toHaveBeenCalledWith(expect.anything(), "Rejected");
  });

  it("moves a card with the Move to list and announces it", async () => {
    const next = allowedNext("Saved")[0];
    const { onMove } = await mount([vacancy("a", "Saved")]);

    const select = host.querySelector<HTMLSelectElement>('select[aria-label="Move Role a to"]')!;
    await act(async () => {
      select.value = next;
      select.dispatchEvent(new Event("change", { bubbles: true }));
    });
    await act(async () => {
      host.querySelector<HTMLButtonElement>('button[aria-label="Move Role a"]')!.click();
    });
    await settle(3);

    expect(onMove).toHaveBeenCalledWith(expect.objectContaining({ id: "a" }), next);
    expect(host.querySelector('[role="status"]')?.textContent).toContain(`Moved "Role a" to ${next}.`);
  });

  it("shows the reason when the server refuses a move", async () => {
    const onMove = vi.fn().mockRejectedValue(new Error("Not allowed from here"));
    await mount([vacancy("a", "Saved")], onMove);

    const next = allowedNext("Saved")[0];
    const select = host.querySelector<HTMLSelectElement>('select[aria-label="Move Role a to"]')!;
    await act(async () => {
      select.value = next;
      select.dispatchEvent(new Event("change", { bubbles: true }));
    });
    await act(async () => {
      host.querySelector<HTMLButtonElement>('button[aria-label="Move Role a"]')!.click();
    });
    await settle(3);

    expect(host.querySelector('[role="alert"]')?.textContent).toBe("Not allowed from here");
  });

  it("moves a card by drag and drop onto a column it may go to", async () => {
    const from = "Saved";
    const to = allowedNext(from)[0];
    const { onMove } = await mount([vacancy("a", from)]);

    await act(async () => {
      card("a").dispatchEvent(new Event("dragstart", { bubbles: true }));
    });
    expect(canMove(from, to)).toBe(true);
    const column = host.querySelector<HTMLElement>(`[data-column="${to}"]`)!;
    expect(column.classList.contains("droppable")).toBe(true);

    await act(async () => {
      column.dispatchEvent(new Event("dragover", { bubbles: true, cancelable: true }));
    });
    expect(column.classList.contains("over")).toBe(true);

    await act(async () => {
      column.dispatchEvent(new Event("drop", { bubbles: true, cancelable: true }));
    });
    await settle(3);

    expect(onMove).toHaveBeenCalledWith(expect.objectContaining({ id: "a" }), to);
  });

  it("clears the drop highlight when the drag leaves or ends", async () => {
    const to = allowedNext("Saved")[0];
    await mount([vacancy("a", "Saved")]);

    await act(async () => {
      card("a").dispatchEvent(new Event("dragstart", { bubbles: true }));
    });
    const column = host.querySelector<HTMLElement>(`[data-column="${to}"]`)!;
    await act(async () => {
      column.dispatchEvent(new Event("dragover", { bubbles: true, cancelable: true }));
      column.dispatchEvent(new Event("dragleave", { bubbles: true }));
    });
    expect(column.classList.contains("over")).toBe(false);

    await act(async () => {
      card("a").dispatchEvent(new Event("dragend", { bubbles: true }));
    });
    expect(host.querySelector(".droppable")).toBeNull();
  });
});
