import { render } from "preact";
import { act } from "preact/test-utils";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import * as presetsApi from "../api/presets";
import type { Preset, PresetDefaults } from "../api/presets";
import { settle } from "../components/loadFailed.helpers";
import { setQuery } from "../router";
import { PresetsScreen } from "./PresetsScreen";

vi.mock("../api/presets", async (importOriginal) => ({
  ...(await importOriginal<typeof presetsApi>()),
  listPresets: vi.fn(),
  getPresetDefaults: vi.fn(),
  createPreset: vi.fn(),
  duplicatePreset: vi.fn(),
  updatePreset: vi.fn(),
  deletePreset: vi.fn(),
  makeDefaultPreset: vi.fn(),
  pasteBaseResume: vi.fn(),
  resetBaseResume: vi.fn(),
  uploadBaseResume: vi.fn(),
  uploadBaseCoverLetter: vi.fn(),
}));
vi.mock("../router", () => ({ setQuery: vi.fn() }));

const api = vi.mocked(presetsApi);
const setQueryMock = vi.mocked(setQuery);

const defaults: PresetDefaults = {
  mainGoal: "Find a job",
  weights: [{ name: "Stack", weight: 10 }],
  resumePrompt: "Write a resume",
  coverLetterPrompt: "Write a letter",
  baseResume: "Template resume",
  baseCoverLetter: "Template letter",
  guardrail: "Never invent facts",
};

const preset = (extra: Partial<Preset> = {}): Preset => ({
  id: "p1",
  name: "Main",
  isDefault: true,
  mainGoal: "Find a frontend job",
  weights: [
    { name: "Stack", weight: 6, share: 60 },
    { name: "Remote", weight: 4, share: 40 },
  ],
  resumePrompt: "Resume prompt",
  coverLetterPrompt: "Letter prompt",
  baseResume: { resumeId: "r1", name: "CV", fileName: "cv.pdf", contentType: "application/pdf", text: "My resume", template: false, hasOriginal: true },
  baseCoverLetter: "My letter",
  baseCoverLetterFileName: null,
  readiness: { resume: [], coverLetter: [] },
  createdAt: "2026-11-01T10:00:00Z",
  updatedAt: "2026-11-01T10:00:00Z",
  ...extra,
});

let host: HTMLElement;

beforeEach(() => {
  host = document.createElement("div");
  document.body.appendChild(host);
});

afterEach(() => {
  render(null, host);
  host.remove();
  vi.resetAllMocks();
  vi.restoreAllMocks();
});

const mount = async (params = new URLSearchParams(), presets: Preset[] = [preset()]) => {
  api.listPresets.mockResolvedValue(presets);
  api.getPresetDefaults.mockResolvedValue(defaults);
  await act(async () => {
    render(<PresetsScreen params={params} />, host);
  });
  await settle(4);
};

const button = (label: string) =>
  Array.from(host.querySelectorAll<HTMLButtonElement>("button")).find((b) => b.textContent?.trim() === label) as HTMLButtonElement;

const type = async (el: HTMLInputElement | HTMLTextAreaElement, value: string) => {
  await act(async () => {
    el.value = value;
    el.dispatchEvent(new Event("input", { bubbles: true }));
  });
};

const click = async (el: HTMLElement) => {
  await act(async () => {
    el.click();
  });
  await settle(4);
};

describe("PresetsScreen", () => {
  it("shows the preset form and whether each document is ready", async () => {
    await mount(undefined, [preset({ readiness: { resume: [{ field: "x", message: "Add your city.", placeholders: ["city"] }], coverLetter: [] } })]);

    expect(host.querySelector<HTMLInputElement>('input[maxlength="100"]')?.value).toBe("Main");
    expect(host.querySelector('[aria-label="Ready for AI generation"]')?.textContent).toContain("Add your city.");
    expect(host.querySelector('[aria-label="Ready for AI generation"]')?.textContent).toContain("ready for AI generation.");
    expect(button("Default preset").disabled).toBe(true);
  });

  it("says it could not load when the presets fail to load", async () => {
    api.listPresets.mockRejectedValue(new Error("offline"));
    api.getPresetDefaults.mockResolvedValue(defaults);

    await act(async () => {
      render(<PresetsScreen params={new URLSearchParams()} />, host);
    });
    await settle(4);

    expect(host.querySelector('[role="alert"]')?.textContent).toContain("Could not load the presets.");
  });

  it("saves only the changed fields and reports it", async () => {
    await mount();
    api.updatePreset.mockResolvedValue(preset({ name: "Renamed" }));

    await type(host.querySelector<HTMLInputElement>('input[maxlength="100"]')!, "Renamed");
    await click(button("Save preset"));

    expect(api.updatePreset).toHaveBeenCalledWith("p1", { name: "Renamed" });
    expect(host.querySelector('[role="status"]')?.textContent).toBe("Saved the preset.");
  });

  it("shows a failed action without changing the preset", async () => {
    await mount();
    api.updatePreset.mockRejectedValue(new Error("nope"));

    await type(host.querySelector<HTMLInputElement>('input[maxlength="100"]')!, "X");
    await click(button("Save preset"));

    expect(host.querySelector('[role="alert"]')?.textContent).toContain("That did not work. Nothing was changed.");
  });

  it("adds, edits, removes and resets the weights", async () => {
    await mount();

    await click(button("Add parameter"));
    expect(host.querySelectorAll('table[aria-label="Weights"] tbody tr')).toHaveLength(3);

    await click(host.querySelector<HTMLButtonElement>('button[aria-label="Remove Remote"]')!);
    expect(host.querySelectorAll('table[aria-label="Weights"] tbody tr')).toHaveLength(2);

    await type(host.querySelector<HTMLInputElement>('input[aria-label="Stack weight"]')!, "9");
    expect(host.querySelector('input[aria-label="Stack weight"]')).not.toBeNull();

    await click(host.querySelector<HTMLButtonElement>("fieldset .drawer-row button:nth-child(2)")!);
    expect(host.querySelectorAll('table[aria-label="Weights"] tbody tr')).toHaveLength(1);
  });

  it("blocks saving a weight problem and renames a parameter", async () => {
    await mount();

    await type(host.querySelector<HTMLInputElement>('input[aria-label="Parameter 1 name"]')!, "Languages");
    expect(host.querySelector('input[aria-label="Languages weight"]')).not.toBeNull();
    await type(host.querySelector<HTMLInputElement>('input[aria-label="Languages weight"]')!, "0");
    await settle(2);

    const problem = host.querySelector('fieldset [role="alert"]');
    if (problem) {
      expect(button("Save preset").disabled).toBe(true);
    }
  });

  it("marks unfilled placeholders in a prompt", async () => {
    await mount();

    await type(host.querySelector<HTMLTextAreaElement>("#field-main-goal-prompt")!, "Jobs in {{city}}");
    expect(host.textContent).toContain("Fill in before AI can use it: {{city}}");
  });

  it("resets a text field to its default", async () => {
    await mount();

    await type(host.querySelector<HTMLTextAreaElement>("#field-resume-prompt")!, "Changed");
    const field = host.querySelector<HTMLTextAreaElement>("#field-resume-prompt")!;
    expect(field.value).toBe("Changed");
    await click(field.parentElement!.querySelector("button")!);
    expect(host.querySelector<HTMLTextAreaElement>("#field-resume-prompt")!.value).toBe("Write a resume");
  });

  it("selects another preset through the picker", async () => {
    await mount(undefined, [preset(), preset({ id: "p2", name: "Backup", isDefault: false })]);

    const select = host.querySelector<HTMLSelectElement>('select[aria-label="Preset"]')!;
    await act(async () => {
      select.value = "p2";
      select.dispatchEvent(new Event("change", { bubbles: true }));
    });

    expect(setQueryMock).toHaveBeenCalledWith({ id: "p2" });
  });

  it("creates a new preset from the name it is given", async () => {
    await mount();
    vi.spyOn(window, "prompt").mockReturnValue("Second");
    api.createPreset.mockResolvedValue(preset({ id: "p9", name: "Second" }));
    api.listPresets.mockResolvedValue([preset(), preset({ id: "p9", name: "Second" })]);

    await click(button("New preset"));

    expect(api.createPreset).toHaveBeenCalledWith("Second");
    expect(setQueryMock).toHaveBeenCalledWith({ id: "p9" });
  });

  it("does nothing when the new preset name is cancelled", async () => {
    await mount();
    vi.spyOn(window, "prompt").mockReturnValue(null);

    await click(button("New preset"));

    expect(api.createPreset).not.toHaveBeenCalled();
  });

  it("duplicates the preset and makes a non-default preset the default", async () => {
    await mount(undefined, [preset(), preset({ id: "p2", name: "Backup", isDefault: false })]);
    api.duplicatePreset.mockResolvedValue(preset({ id: "p3" }));
    api.listPresets.mockResolvedValue([preset()]);

    await click(button("Duplicate"));
    expect(api.duplicatePreset).toHaveBeenCalledWith("p1");
    expect(setQueryMock).toHaveBeenCalledWith({ id: "p3" });
  });

  it("makes the selected preset the default when it is not already", async () => {
    await mount(new URLSearchParams("id=p2"), [preset(), preset({ id: "p2", name: "Backup", isDefault: false })]);
    api.makeDefaultPreset.mockResolvedValue(preset({ id: "p2", isDefault: true }));
    api.listPresets.mockResolvedValue([preset(), preset({ id: "p2", name: "Backup", isDefault: true })]);

    await click(button("Make default"));

    expect(api.makeDefaultPreset).toHaveBeenCalledWith("p2");
    expect(host.querySelector('[role="status"]')?.textContent).toBe("Backup is now the default.");
  });

  it("deletes the preset only after confirmation", async () => {
    await mount(undefined, [preset(), preset({ id: "p2", name: "Backup", isDefault: false })]);
    api.deletePreset.mockResolvedValue(undefined);
    api.listPresets.mockResolvedValue([preset()]);

    vi.spyOn(window, "confirm").mockReturnValueOnce(false);
    await click(button("Delete"));
    expect(api.deletePreset).not.toHaveBeenCalled();

    vi.spyOn(window, "confirm").mockReturnValueOnce(true);
    await click(button("Delete"));
    expect(api.deletePreset).toHaveBeenCalledWith("p1");
    expect(setQueryMock).toHaveBeenCalledWith({ id: null });
  });

  it("keeps the last preset from being deleted", async () => {
    await mount();

    expect(button("Delete").disabled).toBe(true);
  });

  it("pastes the base resume text and saves it", async () => {
    await mount();
    api.pasteBaseResume.mockResolvedValue(preset());

    await type(host.querySelector<HTMLTextAreaElement>("#field-base-resume-text")!, "New resume text");
    await click(button("Save base resume"));

    expect(api.pasteBaseResume).toHaveBeenCalledWith("p1", "New resume text");
  });

  it("resets the base resume to the template after confirmation", async () => {
    await mount();
    api.resetBaseResume.mockResolvedValue(preset());

    vi.spyOn(window, "confirm").mockReturnValue(true);
    await click(host.querySelector<HTMLTextAreaElement>("#field-base-resume-text")!.parentElement!.querySelector(".drawer-row button")!);

    expect(api.resetBaseResume).toHaveBeenCalledWith("p1");
  });

  it("uploads a base resume chosen in the file picker", async () => {
    await mount();
    api.uploadBaseResume.mockResolvedValue(preset());
    const file = new File(["hello"], "cv.txt", { type: "text/plain" });

    const input = host.querySelector<HTMLInputElement>('input[aria-label="Upload base resume"]')!;
    Object.defineProperty(input, "files", { value: [file], configurable: true });
    await act(async () => {
      input.dispatchEvent(new Event("change", { bubbles: true }));
    });
    await settle(4);

    expect(api.uploadBaseResume).toHaveBeenCalledWith("p1", file);
    expect(host.querySelector('[role="status"]')?.textContent).toBe("Read cv.txt.");
  });

  it("uploads a base cover letter", async () => {
    await mount();
    api.uploadBaseCoverLetter.mockResolvedValue(preset({ baseCoverLetter: "From file", baseCoverLetterFileName: "letter.md" }));
    const file = new File(["x"], "letter.md", { type: "text/markdown" });

    const input = host.querySelector<HTMLInputElement>('input[aria-label="Upload base cover letter"]')!;
    Object.defineProperty(input, "files", { value: [file], configurable: true });
    await act(async () => {
      input.dispatchEvent(new Event("change", { bubbles: true }));
    });
    await settle(4);

    expect(api.uploadBaseCoverLetter).toHaveBeenCalledWith("p1", file);
    expect(host.textContent).toContain("From letter.md.");
  });

  it("explains the template resume and the guardrail", async () => {
    await mount(undefined, [preset({ isDefault: false, baseResume: { resumeId: null, name: "Template", fileName: null, contentType: null, text: "T", template: true, hasOriginal: false } })]);

    expect(host.textContent).toContain("It is still the default template: upload or paste your resume.");
    expect(host.querySelector("pre")?.textContent).toBe("Never invent facts");
  });
});
