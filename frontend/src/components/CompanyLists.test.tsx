import { render } from "preact";
import { act } from "preact/test-utils";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import * as companyApi from "../api/companyLists";
import * as profilesApi from "../api/profiles";
import type { SearchProfile } from "../api/profiles";
import { settle } from "./loadFailed.helpers";
import { CompanyLists } from "./CompanyLists";

vi.mock("../api/companyLists", async (importOriginal) => ({
  ...(await importOriginal<typeof companyApi>()),
  listCompanyEntries: vi.fn(),
  addCompanyEntry: vi.fn(),
  removeCompanyEntry: vi.fn(),
}));

const listCompanyEntries = vi.mocked(companyApi.listCompanyEntries);
const addCompanyEntry = vi.mocked(companyApi.addCompanyEntry);
const removeCompanyEntry = vi.mocked(companyApi.removeCompanyEntry);

const profiles = [{ id: "s1", name: "Frontend" }] as SearchProfile[];
let host: HTMLElement;
let onChanged: ReturnType<typeof vi.fn>;

beforeEach(() => {
  host = document.createElement("div");
  document.body.appendChild(host);
  onChanged = vi.fn();
});
afterEach(() => {
  render(null, host);
  host.remove();
  vi.resetAllMocks();
});

const mount = async () => {
  await act(async () => {
    render(<CompanyLists profiles={profiles} onChanged={onChanged as never} />, host);
  });
  await settle(4);
};

const type = async (value: string) => {
  await act(async () => {
    const input = host.querySelector<HTMLInputElement>('input[type="text"]')!;
    input.value = value;
    input.dispatchEvent(new Event("input", { bubbles: true }));
  });
};

const submit = async () => {
  await act(async () => {
    host.querySelector("form")!.dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }));
  });
  await settle(4);
};

describe("CompanyLists", () => {
  it("lists the blocked and allowed companies with the profile they apply to", async () => {
    listCompanyEntries.mockResolvedValue([
      { id: "e1", company: "Spam Ltd", listType: "blocklist", profileId: null },
      { id: "e2", company: "Good Co", listType: "allowlist", profileId: "s1" },
    ] as never);
    await mount();

    expect(host.querySelector('ul[aria-label="Blocklist"]')?.textContent).toContain("Spam Ltd");
    expect(host.querySelector('ul[aria-label="Blocklist"]')?.textContent).toContain("All profiles");
    expect(host.querySelector('ul[aria-label="Allowlist"]')?.textContent).toContain("Frontend");
  });

  it("shows an empty note when a list has no companies", async () => {
    listCompanyEntries.mockResolvedValue([]);
    await mount();
    expect(host.querySelectorAll(".company-list .muted").length).toBeGreaterThan(0);
    expect(host.textContent).toContain("Empty.");
  });

  it("says the lists could not load", async () => {
    listCompanyEntries.mockRejectedValue(new Error("down"));
    await mount();
    expect(host.querySelector('[role="alert"]')?.textContent).toBe("Could not load the company lists.");
  });

  it("adds a company to the chosen list and scope and reports it", async () => {
    listCompanyEntries.mockResolvedValue([]);
    addCompanyEntry.mockResolvedValue(undefined as never);
    await mount();

    await type("Acme");
    await act(async () => {
      const list = host.querySelector<HTMLSelectElement>('select')!;
      list.value = "allowlist";
      list.dispatchEvent(new Event("change", { bubbles: true }));
    });
    await act(async () => {
      const scope = host.querySelectorAll<HTMLSelectElement>("select")[1];
      scope.value = "s1";
      scope.dispatchEvent(new Event("change", { bubbles: true }));
    });
    await submit();

    expect(addCompanyEntry).toHaveBeenCalledWith("Acme", "allowlist", "s1");
    expect(onChanged).toHaveBeenCalledWith("Added to the allowlist. Scores are being recalculated.");
  });

  it("adds to all profiles by default", async () => {
    listCompanyEntries.mockResolvedValue([]);
    addCompanyEntry.mockResolvedValue(undefined as never);
    await mount();

    await type("Beta");
    await submit();

    expect(addCompanyEntry).toHaveBeenCalledWith("Beta", "blocklist", null);
  });

  it("explains a refused company and a failed add", async () => {
    listCompanyEntries.mockResolvedValue([]);
    addCompanyEntry.mockRejectedValueOnce(new profilesApi.ProfileRejected(422, { company: "Already listed" }, "x"));
    await mount();
    await type("Acme");
    await submit();
    expect(host.querySelector('[role="alert"]')?.textContent).toBe("Already listed");

    addCompanyEntry.mockRejectedValueOnce(new Error("network"));
    await submit();
    expect(host.querySelector('[role="alert"]')?.textContent).toBe("The company was not added.");
  });

  it("removes an entry and reports it, or says it failed", async () => {
    listCompanyEntries.mockResolvedValue([{ id: "e1", company: "Spam Ltd", listType: "blocklist", profileId: null }] as never);
    removeCompanyEntry.mockResolvedValue(undefined as never);
    await mount();

    await act(async () => {
      host.querySelector<HTMLButtonElement>('button[aria-label="Remove Spam Ltd from the blocklist"]')!.click();
    });
    await settle(4);

    expect(removeCompanyEntry).toHaveBeenCalledWith("e1");
    expect(onChanged).toHaveBeenCalledWith('Removed "Spam Ltd". Scores are being recalculated.');

    removeCompanyEntry.mockRejectedValueOnce(new Error("gone"));
    await act(async () => {
      host.querySelector<HTMLButtonElement>('button[aria-label="Remove Spam Ltd from the blocklist"]')!.click();
    });
    await settle(4);
    expect(host.querySelector('[role="alert"]')?.textContent).toBe("Could not remove the entry.");
  });
});
