import { render } from "preact";
import { act } from "preact/test-utils";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import * as client from "../api/client";
import * as transfer from "../api/transfer";
import { settle } from "../components/loadFailed.helpers";
import { Transfer } from "./Transfer";

vi.mock("../api/transfer", async (importOriginal) => ({ ...(await importOriginal<typeof transfer>()), importFile: vi.fn() }));
const importFile = vi.mocked(transfer.importFile);
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

const mount = async () => {
  await act(async () => {
    render(<Transfer />, host);
  });
};

const choose = async (file: File | null) => {
  const input = host.querySelector<HTMLInputElement>('input[type="file"]')!;
  Object.defineProperty(input, "files", { value: file ? [file] : [], configurable: true });
  await act(async () => {
    input.dispatchEvent(new Event("change", { bubbles: true }));
  });
};

const importButton = () => host.querySelector<HTMLButtonElement>('button[type="submit"]')!;

describe("Transfer", () => {
  it("offers the three exports as downloads", async () => {
    await mount();
    const links = Array.from(host.querySelectorAll<HTMLAnchorElement>("a.btn"));
    expect(links.map((a) => a.textContent)).toEqual(["CSV", "XLSX", "JSON backup"]);
    expect(links.every((a) => a.hasAttribute("download"))).toBe(true);
  });

  it("keeps Import off until a file is chosen", async () => {
    await mount();
    expect(importButton().disabled).toBe(true);

    await choose(new File(["x"], "v.csv"));
    expect(importButton().disabled).toBe(false);

    await choose(null);
    expect(importButton().disabled).toBe(true);
  });

  it("imports the file and lists the rows that were refused", async () => {
    importFile.mockResolvedValue({
      total: 3,
      imported: 2,
      errors: [{ row: 3, field: "salary", message: "Not a number" }],
    });
    await mount();
    await choose(new File(["x"], "v.csv"));

    await act(async () => {
      importButton().click();
    });
    await settle(3);

    expect(importFile).toHaveBeenCalled();
    expect(host.querySelector('[role="status"]')?.textContent).toContain("Imported 2 of 3 rows.");
    expect(host.querySelector("table.compact")?.textContent).toContain("Not a number");
  });

  it("shows a report without a table when every row was imported", async () => {
    importFile.mockResolvedValue({ total: 1, imported: 1, errors: [] });
    await mount();
    await choose(new File(["x"], "v.json"));

    await act(async () => {
      importButton().click();
    });
    await settle(3);

    expect(host.querySelector("table.compact")).toBeNull();
  });

  it("shows the server's message when the import is refused", async () => {
    importFile.mockRejectedValue(new client.ApiError(413, "File too large"));
    await mount();
    await choose(new File(["x"], "big.csv"));

    await act(async () => {
      importButton().click();
    });
    await settle(3);

    expect(host.querySelector('[role="alert"]')?.textContent).toBe("File too large");
  });

  it("uses a plain message for any other import failure", async () => {
    importFile.mockRejectedValue(new Error("network"));
    await mount();
    await choose(new File(["x"], "v.csv"));

    await act(async () => {
      importButton().click();
    });
    await settle(3);

    expect(host.querySelector('[role="alert"]')?.textContent).toBe("The import failed. Nothing was stored.");
  });
});
