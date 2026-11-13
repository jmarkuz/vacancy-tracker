import { render } from "preact";
import { act } from "preact/test-utils";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import * as search from "../api/search";
import { settle } from "./loadFailed.helpers";
import { CommandPalette } from "./CommandPalette";

vi.mock("../api/search", async (importOriginal) => ({
  ...(await importOriginal<typeof search>()),
  searchVacancies: vi.fn(),
}));

const searchVacancies = vi.mocked(search.searchVacancies);
let host: HTMLElement;
let onClose: ReturnType<typeof vi.fn>;

beforeEach(() => {
  host = document.createElement("div");
  document.body.appendChild(host);
  onClose = vi.fn();
  searchVacancies.mockResolvedValue([]);
});

afterEach(() => {
  render(null, host);
  host.remove();
  vi.resetAllMocks();
});

const mount = async () => {
  await act(async () => {
    render(<CommandPalette onClose={onClose as never} />, host);
  });
  await settle(2);
};

const input = () => host.querySelector<HTMLInputElement>(".palette-input")!;

const type = async (value: string) => {
  await act(async () => {
    input().value = value;
    input().dispatchEvent(new Event("input", { bubbles: true }));
  });
  await act(async () => {
    await new Promise((r) => setTimeout(r, 260));
  });
  await settle(3);
};

const key = async (k: string) => {
  await act(async () => {
    host.querySelector(".palette")!.dispatchEvent(new KeyboardEvent("keydown", { key: k, bubbles: true, cancelable: true }));
  });
};

describe("CommandPalette", () => {
  it("opens with the search box focused and lists the screens", async () => {
    await mount();

    expect(document.activeElement).toBe(input());
    expect(host.querySelectorAll('[role="option"]').length).toBeGreaterThan(0);
  });

  it("closes on Escape and on a click on the backdrop", async () => {
    await mount();

    await key("Escape");
    expect(onClose).toHaveBeenCalledTimes(1);

    await act(async () => {
      host.querySelector<HTMLElement>(".palette-backdrop")!.dispatchEvent(new MouseEvent("mousedown", { bubbles: true }));
    });
    expect(onClose).toHaveBeenCalledTimes(2);
  });

  it("moves the highlight with the arrow keys and stays within the list", async () => {
    await mount();

    await key("ArrowDown");
    expect(host.querySelector('[role="option"].selected')).not.toBeNull();
    const first = host.querySelector('[role="option"].selected')!.id;

    await key("ArrowUp");
    await key("ArrowUp");
    expect(host.querySelector('[role="option"].selected')!.id).not.toBe(first);
  });

  it("goes to the highlighted screen on Enter and closes", async () => {
    await mount();

    await key("Enter");

    expect(onClose).toHaveBeenCalledTimes(1);
    expect(window.location.hash).not.toBe("");
  });

  it("finds vacancies and shows the matching snippet", async () => {
    searchVacancies.mockResolvedValue([{ id: "v9", title: "React dev", company: "Acme", status: "Saved", snippet: "building a [[react]] app" } as never]);
    await mount();

    await type("react");

    expect(searchVacancies).toHaveBeenCalledWith("react", expect.anything());
    expect(host.textContent).toContain("React dev");
    expect(host.querySelector(".palette-snippet mark")?.textContent).toBeTruthy();
  });

  it("says when no vacancy matches and when search is unavailable", async () => {
    await mount();
    await type("zzz");
    expect(host.textContent).toContain("No vacancies match.");

    searchVacancies.mockRejectedValue(new Error("down"));
    await type("qqq");
    expect(host.textContent).toContain("Search is not available right now.");
  });

  it("keeps Tab inside the palette", async () => {
    await mount();
    const event = new KeyboardEvent("keydown", { key: "Tab", bubbles: true, cancelable: true });
    await act(async () => {
      host.querySelector(".palette")!.dispatchEvent(event);
    });
    expect(event.defaultPrevented).toBe(true);
  });
});
