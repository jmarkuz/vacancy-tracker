import { render } from "preact";
import { act } from "preact/test-utils";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import * as profilesApi from "../api/profiles";
import type { Meta, SearchProfile } from "../api/profiles";
import * as recalc from "../api/recalculation";
import * as sourcesApi from "../api/sources";
import { settle } from "../components/loadFailed.helpers";
import { setQuery } from "../router";
import { Profiles } from "./Profiles";

vi.mock("../api/profiles", async (importOriginal) => ({
  ...(await importOriginal<typeof profilesApi>()),
  listProfiles: vi.fn(),
  getMeta: vi.fn(),
  createProfile: vi.fn(),
  deleteProfile: vi.fn(),
  importProfile: vi.fn(),
}));
vi.mock("../api/recalculation", async (importOriginal) => ({
  ...(await importOriginal<typeof recalc>()),
  startRecalculation: vi.fn(),
  getRecalculationProgress: vi.fn(),
}));
vi.mock("../api/sources", async (importOriginal) => ({
  ...(await importOriginal<typeof sourcesApi>()),
  listSources: vi.fn(),
}));
vi.mock("../router", () => ({ setQuery: vi.fn() }));
vi.mock("../components/ProfileEditor", () => ({
  ProfileEditor: (props: { initial: { name: string } }) => <div class="editor-stub">{props.initial.name}</div>,
}));
vi.mock("../components/CompanyLists", () => ({ CompanyLists: () => <div class="lists-stub" /> }));
vi.mock("../components/Wizard", () => ({
  Wizard: (props: { onSkip: () => void }) => (
    <button class="wizard-stub" type="button" onClick={props.onSkip}>
      Skip wizard
    </button>
  ),
}));

const listProfiles = vi.mocked(profilesApi.listProfiles);
const getMeta = vi.mocked(profilesApi.getMeta);
const createProfile = vi.mocked(profilesApi.createProfile);
const deleteProfile = vi.mocked(profilesApi.deleteProfile);
const importProfile = vi.mocked(profilesApi.importProfile);
const startRecalculation = vi.mocked(recalc.startRecalculation);
const getRecalculationProgress = vi.mocked(recalc.getRecalculationProgress);
const listSources = vi.mocked(sourcesApi.listSources);
const setQueryMock = vi.mocked(setQuery);

const meta: Meta = {
  fields: [],
  modes: [],
  missingValuePolicies: [],
  minWeight: 0,
  maxWeight: 10,
  defaultThresholds: { fitBorderline: 40 } as never,
};

const profile = (extra: Partial<SearchProfile> = {}): SearchProfile =>
  ({
    id: "s1",
    name: "Frontend",
    enabled: true,
    sources: ["hh"],
    criteria: [{ field: "title" }],
    version: 3,
    updatedAt: "2026-11-02T10:00:00Z",
    ...extra,
  }) as SearchProfile;


/** jsdom's File has no text() that the app can read here, so the test supplies the contents itself. */
const fileWith = (text: string, name: string) => Object.assign(new File([text], name), { text: async () => text });

let host: HTMLElement;

beforeEach(() => {
  host = document.createElement("div");
  document.body.appendChild(host);
  getMeta.mockResolvedValue(meta);
  listSources.mockResolvedValue([]);
  getRecalculationProgress.mockResolvedValue(null);
});

afterEach(() => {
  render(null, host);
  host.remove();
  vi.resetAllMocks();
  vi.restoreAllMocks();
});

const mount = async (params = new URLSearchParams(), profiles: SearchProfile[] = [profile()]) => {
  listProfiles.mockResolvedValue(profiles);
  await act(async () => {
    render(<Profiles params={params} />, host);
  });
  await settle(5);
};

const button = (label: string) =>
  Array.from(host.querySelectorAll<HTMLButtonElement>("button")).find((b) => b.textContent?.trim() === label) as HTMLButtonElement;

const click = async (el: HTMLElement) => {
  await act(async () => {
    el.click();
  });
  await settle(4);
};

describe("Profiles", () => {
  it("lists the search profiles with their rules and version", async () => {
    await mount();

    expect(host.querySelector('table[aria-label="Search profiles"]')).not.toBeNull();
    expect(host.textContent).toContain("Frontend");
    expect(host.textContent).toContain("Enabled");
    expect(host.querySelector(".numeric.num")?.textContent).toBe("1");
    expect(host.querySelector('a[href="/api/profiles/s1/export?format=yaml"]')).not.toBeNull();
  });

  it("invites the first profile when there are none", async () => {
    await mount(undefined, []);

    expect(host.querySelector("h2")?.textContent).toBe("No search profiles yet");
    await click(button("Set up with the wizard"));
    expect(setQueryMock).toHaveBeenCalledWith({ wizard: "1" });
  });

  it("says it could not load the profiles", async () => {
    listProfiles.mockRejectedValue(new Error("offline"));
    await act(async () => {
      render(<Profiles params={new URLSearchParams()} />, host);
    });
    await settle(4);

    expect(host.querySelector('[role="alert"]')?.textContent).toContain("Could not load the profiles.");
  });

  it("opens the editor for a stored profile and says when it is gone", async () => {
    await mount(new URLSearchParams("edit=s1"));
    expect(host.querySelector(".editor-stub")?.textContent).toBe("Frontend");

    render(null, host);
    await mount(new URLSearchParams("edit=missing"));
    expect(host.textContent).toContain("That profile no longer exists.");
    await click(button("Back to the list"));
    expect(setQueryMock).toHaveBeenCalledWith({ edit: null });
  });

  it("opens a new profile in the editor", async () => {
    await mount(new URLSearchParams("edit=new"));

    expect(host.querySelector(".editor-stub")).not.toBeNull();
    await click(button("New profile"));
    expect(setQueryMock).toHaveBeenCalledWith({ edit: "new" });
  });

  it("starts a recalculation and shows the running progress", async () => {
    await mount();
    startRecalculation.mockResolvedValue({ jobId: "j1", started: true });
    getRecalculationProgress.mockResolvedValue({ jobId: "j1", state: "RUNNING", processed: 2, total: 4, failed: 0, startedAt: "x", finishedAt: null });

    await click(button("Recalculate scores"));

    expect(startRecalculation).toHaveBeenCalled();
    expect(host.querySelector('progress[aria-label="Recalculation progress"]')).not.toBeNull();
    expect(button("Recalculating…").disabled).toBe(true);
  });

  it("tells the user when a recalculation is already running", async () => {
    await mount();
    startRecalculation.mockResolvedValue({ jobId: "j1", started: false });

    await click(button("Recalculate scores"));

    expect(host.textContent).toContain("A recalculation is already running.");
  });

  it("reports a recalculation that could not start", async () => {
    await mount();
    startRecalculation.mockRejectedValue(new Error("Busy server"));

    await click(button("Recalculate scores"));

    expect(host.textContent).toContain("Busy server");
  });

  it("follows a recalculation already running on open and reports its end", async () => {
    vi.useFakeTimers();
    try {
      getRecalculationProgress.mockResolvedValueOnce({ jobId: "j0", state: "RUNNING", processed: 1, total: 2, failed: 0, startedAt: "x", finishedAt: null });
      listProfiles.mockResolvedValue([profile()]);
      await act(async () => {
        render(<Profiles params={new URLSearchParams()} />, host);
      });
      await settle(4);

      getRecalculationProgress.mockResolvedValue({ jobId: "j0", state: "COMPLETED", processed: 2, total: 2, failed: 1, startedAt: "x", finishedAt: "y" });
      await act(async () => {
        vi.advanceTimersByTime(800);
      });
      await settle(4);

      expect(host.querySelector('[role="status"]')).not.toBeNull();
      expect(host.textContent).toMatch(/2/);
    } finally {
      vi.useRealTimers();
    }
  });

  it("duplicates a profile as a copy and opens it", async () => {
    await mount();
    createProfile.mockResolvedValue(profile({ id: "s2", name: "Frontend (copy)" }));

    await click(button("Duplicate"));

    expect(createProfile).toHaveBeenCalled();
    expect(setQueryMock).toHaveBeenCalledWith({ edit: "s2" });
  });

  it("reports a failed duplicate", async () => {
    await mount();
    createProfile.mockRejectedValue(new Error("Name taken"));

    await click(button("Duplicate"));

    expect(host.textContent).toContain("Name taken");
  });

  it("deletes a profile only after confirmation", async () => {
    await mount();
    deleteProfile.mockResolvedValue(undefined);
    listProfiles.mockResolvedValue([]);

    vi.spyOn(window, "confirm").mockReturnValueOnce(false);
    await click(button("Delete"));
    expect(deleteProfile).not.toHaveBeenCalled();

    vi.spyOn(window, "confirm").mockReturnValueOnce(true);
    await click(button("Delete"));
    expect(deleteProfile).toHaveBeenCalledWith("s1");
    expect(host.textContent).toContain('Deleted "Frontend"');
  });

  it("reports a failed delete", async () => {
    await mount();
    deleteProfile.mockRejectedValue(new Error("In use"));
    vi.spyOn(window, "confirm").mockReturnValue(true);

    await click(button("Delete"));

    expect(host.textContent).toContain("In use");
  });

  it("imports a file and opens the imported profile", async () => {
    await mount();
    importProfile.mockResolvedValue({ created: true, profile: profile({ id: "s5", name: "Imported" }) } as never);
    listProfiles.mockResolvedValue([profile()]);

    const input = host.querySelector<HTMLInputElement>('input[data-role="import-file"]')!;
    const file = fileWith("name: Imported", "p.yml");
    Object.defineProperty(input, "files", { value: [file], configurable: true });
    await act(async () => {
      input.dispatchEvent(new Event("change", { bubbles: true }));
    });
    await settle(12);

    expect(importProfile).toHaveBeenCalledWith("name: Imported");
    expect(host.textContent).toContain('Imported "Imported" as a new profile');
    expect(setQueryMock).toHaveBeenCalledWith({ edit: "s5" });
  });

  it("names the version when an import updates an existing profile", async () => {
    await mount();
    importProfile.mockResolvedValue({ created: false, profile: profile({ version: 4 }) } as never);

    const input = host.querySelector<HTMLInputElement>('input[data-role="import-file"]')!;
    Object.defineProperty(input, "files", { value: [fileWith("x", "p.json")], configurable: true });
    await act(async () => {
      input.dispatchEvent(new Event("change", { bubbles: true }));
    });
    await settle(12);

    expect(host.textContent).toContain('as version 4 of the existing profile');
  });

  it("lists what was wrong with a file that is rejected", async () => {
    await mount();
    importProfile.mockRejectedValue(new profilesApi.ProfileRejected(422, { "criteria[0].field": "Unknown field" }, "rejected"));

    const input = host.querySelector<HTMLInputElement>('input[data-role="import-file"]')!;
    Object.defineProperty(input, "files", { value: [fileWith("x", "p.yml")], configurable: true });
    await act(async () => {
      input.dispatchEvent(new Event("change", { bubbles: true }));
    });
    await settle(4);

    expect(host.textContent).toContain("The file was not imported. Nothing was changed.");
    expect(host.querySelector(".mono")?.textContent).toBeTruthy();

    await click(button("Dismiss"));
    expect(host.textContent).not.toContain("The file was not imported.");
  });

  it("reports a file that cannot be read or sent", async () => {
    await mount();
    importProfile.mockRejectedValue(new Error("network"));

    const input = host.querySelector<HTMLInputElement>('input[data-role="import-file"]')!;
    Object.defineProperty(input, "files", { value: [fileWith("x", "p.yml")], configurable: true });
    await act(async () => {
      input.dispatchEvent(new Event("change", { bubbles: true }));
    });
    await settle(4);

    expect(host.textContent).toContain("Could not read or send the file.");
  });

  it("opens the wizard and skips it back to the editor for a new profile", async () => {
    await mount(new URLSearchParams("wizard=1"), []);

    await click(host.querySelector<HTMLButtonElement>(".wizard-stub")!);

    expect(setQueryMock).toHaveBeenCalledWith({ wizard: null, edit: "new" });
  });
});
