import { render } from "preact";
import { act } from "preact/test-utils";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { Pipeline } from "./Pipeline";

let host: HTMLElement;

const row = (n: number) => ({
  id: `id-${n}`, title: `Backend ${n}`, company: `Acme ${n}`, url: null, canonicalUrl: null, description: null, notes: null,
  contact: null, nextActionNote: null, userEditedFields: [], location: "Kyiv", country: "UA", workMode: "remote", status: "New",
  priority: null, salaryMin: 3000, salaryMax: 4000, currency: "USD", salaryPeriod: "month", tags: [], nextActionAt: null,
  firstSeenAt: "2026-11-01T10:00:00Z", lastSeenAt: "2026-11-02T10:00:00Z", isNew: true,
});
let items: ReturnType<typeof row>[] = [];
let total = 0;

function answer(url: string): unknown {
  if (url.startsWith("/api/vacancies")) {
    return { items, page: 0, size: 25, total };
  }
  if (url.startsWith("/api/views")) {
    return [];
  }
  return [];
}

beforeEach(() => {
  items = [];
  total = 0;
  vi.stubGlobal(
    "fetch",
    vi.fn(async (url: string) => ({ ok: true, status: 200, json: async () => answer(url), text: async () => "[]" })),
  );
  host = document.createElement("div");
  document.body.appendChild(host);
});

afterEach(() => {
  render(null, host);
  host.remove();
  vi.unstubAllGlobals();
});

const show = async (query = "") => {
  await act(async () => {
    render(<Pipeline params={new URLSearchParams(query)} dataVersion={0} />, host);
  });
  for (let i = 0; i < 3; i++) {
    await act(async () => {
      await Promise.resolve();
    });
  }
};

/** The control with these words, and the icon drawn in it. */
const control = (label: string) => {
  const el = [...host.querySelectorAll("button, summary")].find((b) => b.textContent?.trim() === label);
  return { el, icon: el?.querySelector("svg.icon")?.getAttribute("data-icon") ?? null };
};

describe("Pipeline: the icons of the buttons", () => {
  it("puts an icon beside the layout, columns and add buttons and keeps their words", async () => {
    await show();

    expect(control("Table").icon).toBe("table");
    expect(control("Board").icon).toBe("board");
    expect(control("Columns").icon).toBe("columns");
    expect(control("Add vacancy").icon).toBe("plus");
  });

  it("puts one on the filter form", async () => {
    await show();

    expect(control("Add filter").icon).toBe("filter");
  });

  it("keeps the layout switch a pair of toggle buttons with the same names", async () => {
    await show();

    const table = control("Table").el as HTMLElement;
    const board = control("Board").el as HTMLElement;
    expect(table.getAttribute("aria-pressed")).toBe("true");
    expect(board.getAttribute("aria-pressed")).toBe("false");

    await show("layout=board");
    expect((control("Board").el as HTMLElement).getAttribute("aria-pressed")).toBe("true");
  });

  it("never leaves an icon as the only thing in a button: every icon is hidden from screen readers", async () => {
    await show();

    const icons = [...host.querySelectorAll("svg.icon")];
    expect(icons.length).toBeGreaterThanOrEqual(5);
    for (const svg of icons) {
      expect(svg.getAttribute("aria-hidden")).toBe("true");
      expect(svg.closest("button, summary")?.textContent?.trim().length ?? 0).toBeGreaterThan(0);
    }
  });
});

const press = async (el: Element | undefined) => {
  await act(async () => {
    (el as HTMLElement).click();
  });
  for (let i = 0; i < 3; i++) {
    await act(async () => {
      await Promise.resolve();
    });
  }
};

describe("Pipeline: the buttons with vacancies on the screen", () => {
  it("shows the bulk bar with Apply and Clear selection icons once a row is picked, and clears it again", async () => {
    items = [row(1), row(2)];
    total = 2;
    await show();

    const boxes = [...host.querySelectorAll('tbody input[type="checkbox"]')];
    expect(boxes.length).toBe(2);
    await press(boxes[0]);

    expect(host.querySelector('[aria-label="Bulk actions"]')).not.toBeNull();
    expect(control("Apply").icon).toBe("check");
    expect(control("Clear selection").icon).toBe("close");

    await press(control("Clear selection").el);
    expect(host.querySelector('[aria-label="Bulk actions"]')).toBeNull();
  });

  it("selects the whole page from the header box", async () => {
    items = [row(1), row(2)];
    total = 2;
    await show();

    await press(host.querySelector('thead input[type="checkbox"]') ?? undefined);

    expect(host.querySelector('[role="status"]')?.textContent).toContain("2 selected");
  });

  it("puts arrows on Previous and Next when there is more than one page", async () => {
    items = [row(1)];
    total = 250;
    await show();

    expect(control("Previous").icon).toBe("chevron-left");
    expect(control("Next").icon).toBe("chevron-right");
    expect((control("Previous").el as HTMLButtonElement).disabled).toBe(true);
  });

  it("opens the add form from Add vacancy and the column list from Columns", async () => {
    items = [row(1)];
    total = 1;
    await show();

    await press(control("Add vacancy").el);
    expect((control("Add vacancy").el as HTMLElement).getAttribute("aria-expanded")).toBe("true");

    const columns = control("Columns").el as HTMLElement;
    await press(columns);
    expect(host.querySelectorAll('input[type="checkbox"]').length).toBeGreaterThan(3);
  });

  it("switches to the board and back with the layout buttons", async () => {
    items = [row(1)];
    total = 1;
    await show();

    await press(control("Board").el);
    expect(window.location.hash + window.location.search).toContain("layout=board");
    await press(control("Table").el);
    expect(window.location.hash + window.location.search).not.toContain("layout=board");
  });
});

describe("Pipeline: more of the buttons at work", () => {
  it("sorts from a column header, opens a vacancy from its title and moves to the next page", async () => {
    items = [row(1)];
    total = 250;
    await show();

    await press(host.querySelector(".sort-button") ?? undefined);
    expect(window.location.hash + window.location.search).toContain("sort=");

    await press(host.querySelector(".row-open") ?? undefined);
    expect(window.location.hash + window.location.search).toContain("open=id-1");

    await press(control("Next").el);
    expect(window.location.hash + window.location.search).toContain("page=1");
  });

  it("applies a status to the picked rows through the bulk bar", async () => {
    items = [row(1)];
    total = 1;
    const calls: string[] = [];
    vi.stubGlobal(
      "fetch",
      vi.fn(async (url: string) => {
        calls.push(url);
        const body = url.includes("/api/bulk/")
          ? { changed: [{ id: "id-1", from: "New", to: "Applied" }], skipped: [] }
          : answer(url);
        return { ok: true, status: 200, json: async () => body, text: async () => "[]" };
      }),
    );
    await show();

    await press(host.querySelector('tbody input[type="checkbox"]') ?? undefined);
    const select = host.querySelector('select[aria-label="Change status to"]') as HTMLSelectElement;
    const option = [...select.options].find((o) => o.value !== "");
    expect(option).toBeDefined();
    await act(async () => {
      select.value = option!.value;
      select.dispatchEvent(new Event("change", { bubbles: true }));
    });
    await press(control("Apply").el);

    expect(calls.some((u) => u.includes("/api/bulk/vacancies/status"))).toBe(true);
  });

  it("shows Retry with a refresh icon when the list fails to load, and asks again", async () => {
    let failing = true;
    vi.stubGlobal(
      "fetch",
      vi.fn(async (url: string) => {
        if (failing && url.startsWith("/api/vacancies")) {
          return { ok: false, status: 500, json: async () => ({ title: "Boom" }), text: async () => "" };
        }
        return { ok: true, status: 200, json: async () => answer(url), text: async () => "[]" };
      }),
    );
    await show();

    expect(control("Retry").icon).toBe("refresh");
    failing = false;
    await press(control("Retry").el);
    expect(control("Retry").el).toBeUndefined();
  });
});

describe("Pipeline: the dense table", () => {
  it("marks the table and every column so the styles can size them, and keeps long text readable in a tooltip", async () => {
    items = [{ ...row(1), location: "Time zone: Europe, Latin America and more" }];
    total = 1;
    await show();

    expect(host.querySelector("table.pipeline-table")).not.toBeNull();
    expect(host.querySelector("th.col-title")).not.toBeNull();
    expect(host.querySelector("td.col-company")).not.toBeNull();
    const clipped = host.querySelector("td.col-location .cell-clip");
    expect(clipped?.getAttribute("title")).toBe("Time zone: Europe, Latin America and more");
    expect(host.querySelector(".title-cell .row-open")?.getAttribute("title")).toBe("Backend 1");
  });

  it("shows a dash for an empty clipped cell", async () => {
    items = [{ ...row(1), location: null as unknown as string }];
    total = 1;
    await show();

    expect(host.querySelector("td.col-location .cell-clip")?.textContent).toBe("-");
  });
});

describe("Pipeline: resizable columns", () => {
  const handle = (label: string) => host.querySelector(`[role="separator"][aria-label="Resize ${label} column"]`) as HTMLElement;
  const colOf = (id: string) => {
    const index = [...host.querySelectorAll("thead th")].findIndex((th) => th.classList.contains(`col-${id}`));
    return host.querySelectorAll("colgroup col")[index] as HTMLElement;
  };
  const keydown = (el: HTMLElement, key: string) =>
    act(async () => {
      el.dispatchEvent(new KeyboardEvent("keydown", { key, bubbles: true, cancelable: true }));
    });

  beforeEach(() => {
    window.localStorage.removeItem("vt:pipeline-column-widths");
    items = [row(1)];
    total = 1;
  });

  it("gives every column header a resize handle and a width", async () => {
    await show();

    const handles = host.querySelectorAll('[role="separator"]');
    expect(handles.length).toBe(host.querySelectorAll("thead th:not(.check-col)").length);
    expect(colOf("company").style.width).toBe("170px");
    expect(colOf("title").style.width).toBe("");
  });

  it("widens and narrows a column with the arrow keys and remembers it", async () => {
    await show();

    await keydown(handle("Company"), "ArrowRight");
    expect(colOf("company").style.width).toBe("186px");
    await keydown(handle("Company"), "ArrowLeft");
    await keydown(handle("Company"), "ArrowLeft");
    expect(colOf("company").style.width).toBe("154px");
    expect(window.localStorage.getItem("vt:pipeline-column-widths")).toContain('"company":154');

    await show();
    expect(colOf("company").style.width).toBe("154px");
  });

  it("follows a drag of the edge with the pointer", async () => {
    // jsdom has no pointer event handlers on elements; with them Preact listens for the lower-case names, as in a browser.
    for (const name of ["pointerdown", "pointermove", "pointerup", "pointercancel"]) {
      Object.defineProperty(HTMLElement.prototype, `on${name}`, { value: null, configurable: true, writable: true });
    }
    await show();
    const edge = handle("Company");
    const fire = (type: string, x: number) =>
      act(async () => {
        const e = new MouseEvent(type, { clientX: x, bubbles: true, cancelable: true });
        Object.defineProperty(e, "pointerId", { value: 1 });
        edge.dispatchEvent(e);
      });

    await fire("pointerdown", 500);
    await fire("pointermove", 560);
    expect(colOf("company").style.width).toBe("230px");
    await fire("pointerup", 560);
    await fire("pointermove", 700);
    expect(colOf("company").style.width).toBe("230px");
  });

  it("goes back to the default on a double click or Enter", async () => {
    await show();
    await keydown(handle("Company"), "ArrowRight");
    await act(async () => {
      handle("Company").dispatchEvent(new MouseEvent("dblclick", { bubbles: true }));
    });
    expect(colOf("company").style.width).toBe("170px");

    await keydown(handle("Company"), "ArrowRight");
    await keydown(handle("Company"), "Enter");
    expect(colOf("company").style.width).toBe("170px");
    expect(window.localStorage.getItem("vt:pipeline-column-widths")).toBe("{}");
  });

  it("does not sort when the edge is clicked and keeps the table at least as wide as its columns", async () => {
    await show();

    await act(async () => {
      handle("Company").click();
    });
    expect(window.location.hash).not.toContain("sort=company");
    const table = host.querySelector("table.pipeline-table") as HTMLElement;
    expect(parseInt(table.style.minWidth, 10)).toBeGreaterThan(280);
  });
});
