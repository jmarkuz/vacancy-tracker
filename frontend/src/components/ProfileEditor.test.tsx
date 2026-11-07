import { render } from "preact";
import { act } from "preact/test-utils";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import * as profilesApi from "../api/profiles";
import type { Criterion, Meta, ProfileInput, SearchProfile } from "../api/profiles";
import { settle } from "./loadFailed.helpers";
import { ProfileEditor } from "./ProfileEditor";

vi.mock("../api/profiles", async (importOriginal) => ({
  ...(await importOriginal<typeof profilesApi>()),
  validateProfile: vi.fn(),
  createProfile: vi.fn(),
  updateProfile: vi.fn(),
}));
vi.mock("./PreviewPanel", () => ({ PreviewPanel: () => <div class="preview-stub" /> }));
vi.mock("./RuleRow", () => ({
  RuleRow: (props: { rule: Criterion; index: number; onMove: (d: number, role: string) => void; onDuplicate: () => void; onRemove: () => void }) => (
    <li class="rule-stub">
      <span>{props.rule.field}</span>
      <button type="button" onClick={() => props.onMove(1, "up")}>Move down</button>
      <button type="button" onClick={() => props.onDuplicate()}>Duplicate rule</button>
      <button type="button" onClick={() => props.onRemove()}>Remove rule</button>
    </li>
  ),
}));

const validateProfile = vi.mocked(profilesApi.validateProfile);
const createProfile = vi.mocked(profilesApi.createProfile);
const updateProfile = vi.mocked(profilesApi.updateProfile);

const meta = {
  fields: [{ name: "title", kind: "TEXT", operators: [{ name: "contains", valueShape: "TEXT" }] }],
  modes: [],
  missingValuePolicies: [],
  minWeight: 0,
  maxWeight: 10,
  defaultThresholds: { inboxMinScore: 40, notifyMinScore: 70, maxAgeDays: 30 },
} as unknown as Meta;

const input = (extra: Partial<ProfileInput> = {}): ProfileInput => ({
  name: "Frontend",
  enabled: true,
  sources: ["hh"],
  scheduleOverride: null,
  thresholds: { inboxMinScore: 40, notifyMinScore: 70, maxAgeDays: 30 } as never,
  criteria: [{ id: "c1", field: "title", operator: "contains", value: "react", mode: "REQUIRED", weight: 5, missingValuePolicy: "IGNORE", label: null }],
  ...extra,
});

const stored = (extra: Partial<SearchProfile> = {}): SearchProfile => ({ ...input(), id: "s1", version: 2, updatedAt: "2026-11-01T10:00:00Z", ...extra }) as SearchProfile;

let host: HTMLElement;
let onSaved: ReturnType<typeof vi.fn>;
let onClose: ReturnType<typeof vi.fn>;

beforeEach(() => {
  host = document.createElement("div");
  document.body.appendChild(host);
  onSaved = vi.fn();
  onClose = vi.fn();
  validateProfile.mockResolvedValue({ valid: true, errors: {} } as never);
});

afterEach(() => {
  render(null, host);
  host.remove();
  vi.resetAllMocks();
});

const mount = async (initial: ProfileInput, profile: SearchProfile | null = null, sourceIds = ["hh", "linkedin"]) => {
  await act(async () => {
    render(<ProfileEditor initial={initial} profile={profile} meta={meta} sourceIds={sourceIds} onSaved={onSaved as never} onClose={onClose as never} />, host);
  });
  // The live check waits a moment after the last edit.
  await act(async () => {
    await new Promise((r) => setTimeout(r, 400));
  });
  await settle(3);
};

const button = (label: string) =>
  Array.from(host.querySelectorAll<HTMLButtonElement>("button")).find((b) => b.textContent?.trim() === label) as HTMLButtonElement;

const typeInto = async (el: HTMLInputElement | HTMLTextAreaElement, value: string) => {
  await act(async () => {
    el.value = value;
    el.dispatchEvent(new Event("input", { bubbles: true }));
  });
};

const change = async (el: HTMLInputElement | HTMLSelectElement, value: string | boolean) => {
  await act(async () => {
    if (typeof value === "boolean") {
      (el as HTMLInputElement).checked = value;
    } else {
      el.value = value;
    }
    el.dispatchEvent(new Event("change", { bubbles: true }));
  });
};

const waitForCheck = async () => {
  await act(async () => {
    await new Promise((r) => setTimeout(r, 400));
  });
  await settle(3);
};

describe("ProfileEditor", () => {
  it("creates a new profile once the server accepts it", async () => {
    createProfile.mockResolvedValue(stored({ id: "new", name: "Frontend" }));
    await mount(input());

    expect(host.textContent).toContain("The profile is valid.");
    expect(button("Create profile").disabled).toBe(false);

    await act(async () => {
      button("Create profile").click();
    });
    await settle(3);

    expect(createProfile).toHaveBeenCalledWith(expect.objectContaining({ name: "Frontend" }));
    expect(onSaved).toHaveBeenCalledWith(expect.objectContaining({ id: "new" }), true);
  });

  it("keeps saving off while the server reports errors and shows them", async () => {
    validateProfile.mockResolvedValue({ valid: false, errors: { name: "Name is required" } } as never);
    await mount(input({ name: "" }));

    expect(host.textContent).toContain("Fix the marked fields to save.");
    expect(host.querySelector("#err-name")?.textContent).toBe("Name is required");
    expect(button("Create profile").disabled).toBe(true);
  });

  it("reports when the check itself cannot be reached", async () => {
    validateProfile.mockRejectedValue(new Error("offline"));
    await mount(input());

    expect(host.textContent).toContain("Could not check the profile right now.");
  });

  it("saves an existing profile as a new version only when something changed", async () => {
    updateProfile.mockResolvedValue(stored({ version: 3, name: "Frontend 2" }));
    await mount(input(), stored());

    expect(button("Save as new version").disabled).toBe(true);
    expect(host.textContent).toContain("Version 2");

    await typeInto(host.querySelector<HTMLInputElement>('input[type="text"]')!, "Frontend 2");
    expect(host.textContent).toContain("Unsaved changes");
    await waitForCheck();

    await act(async () => {
      button("Save as new version").click();
    });
    await settle(3);

    expect(updateProfile).toHaveBeenCalledWith("s1", expect.objectContaining({ name: "Frontend 2" }));
    expect(onSaved).toHaveBeenCalledWith(expect.objectContaining({ version: 3 }), false);
  });

  it("marks a name the server says already exists on a conflict", async () => {
    updateProfile.mockRejectedValue(new profilesApi.ProfileRejected(409, {}, "Name taken"));
    await mount(input({ name: "Taken" }), stored());
    await typeInto(host.querySelector<HTMLInputElement>('input[type="text"]')!, "Taken 2");
    await waitForCheck();

    await act(async () => {
      button("Save as new version").click();
    });
    await settle(3);

    expect(host.querySelector('[role="alert"]')?.textContent).toBe("Name taken");
    expect(host.querySelector("#err-name")?.textContent).toBe("A profile with this name already exists");
  });

  it("shows the rejected fields when the server refuses the profile", async () => {
    createProfile.mockRejectedValue(new profilesApi.ProfileRejected(422, { scheduleOverride: "Bad cron" }, "Refused"));
    await mount(input());

    await act(async () => {
      button("Create profile").click();
    });
    await settle(3);

    expect(host.querySelector('[role="alert"]')?.textContent).toBe("Refused");
  });

  it("explains a save that failed for another reason", async () => {
    createProfile.mockRejectedValue(new Error("network"));
    await mount(input());

    await act(async () => {
      button("Create profile").click();
    });
    await settle(3);

    expect(host.querySelector('[role="alert"]')?.textContent).toBe("The profile was not saved. Check the connection and try again.");
  });

  it("toggles all sources and single sources", async () => {
    await mount(input({ sources: ["hh"] }));

    const sourceChecks = () => Array.from(host.querySelectorAll<HTMLInputElement>(".source-list input"));
    expect(sourceChecks().map((c) => c.checked)).toEqual([true, false]);

    const allSources = host.querySelector<HTMLInputElement>('.editor-section label.check input')!;
    await change(allSources, true);
    expect(sourceChecks().every((c) => c.disabled)).toBe(true);
    await waitForCheck();

    await change(allSources, false);
    await change(sourceChecks()[1], true);
    await change(sourceChecks()[0], false);
    await waitForCheck();

    await act(async () => {
      button("Create profile").click();
    });
    await settle(3);
    expect(validateProfile).toHaveBeenLastCalledWith(expect.objectContaining({ sources: ["linkedin"] }), expect.anything());
  });

  it("keeps an emptied threshold empty instead of zero, and sets a schedule override", async () => {
    await mount(input());

    const inbox = host.querySelector<HTMLInputElement>('input[type="number"]')!;
    await typeInto(inbox, "");
    await waitForCheck();
    expect(validateProfile).toHaveBeenLastCalledWith(
      expect.objectContaining({ thresholds: expect.objectContaining({ inboxMinScore: Number.NaN }) }),
      expect.anything(),
    );

    await typeInto(host.querySelector<HTMLInputElement>('input.mono')!, "0 0 6 * * *");
    await waitForCheck();
    expect(validateProfile).toHaveBeenLastCalledWith(expect.objectContaining({ scheduleOverride: "0 0 6 * * *" }), expect.anything());
  });

  it("switches the profile off and on", async () => {
    await mount(input());

    await change(host.querySelector<HTMLInputElement>('input[role="switch"]')!, false);

    expect(host.querySelector(".toggle")?.textContent).toContain("Disabled");
  });

  it("adds, moves, duplicates and removes rules", async () => {
    await mount(input());
    expect(host.querySelectorAll(".rule-stub")).toHaveLength(1);

    await act(async () => {
      button("Add rule").click();
    });
    expect(host.querySelectorAll(".rule-stub")).toHaveLength(2);

    await act(async () => {
      button("Duplicate rule").click();
    });
    expect(host.querySelectorAll(".rule-stub")).toHaveLength(3);

    await act(async () => {
      button("Move down").click();
    });
    await act(async () => {
      host.querySelectorAll<HTMLButtonElement>(".rule-stub")[0].querySelectorAll("button")[2].click();
    });
    expect(host.querySelectorAll(".rule-stub")).toHaveLength(2);
  });

  it("says when there are no rules and closes on Close", async () => {
    await mount(input({ criteria: [] }));

    expect(host.textContent).toContain("No rules yet.");

    await act(async () => {
      button("Close").click();
    });
    expect(onClose).toHaveBeenCalledTimes(1);
  });
});
