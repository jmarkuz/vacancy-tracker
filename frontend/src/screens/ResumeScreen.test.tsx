import { render } from "preact";
import { act } from "preact/test-utils";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import * as client from "../api/client";
import * as resumeApi from "../api/resume";
import type { Resume } from "../api/resume";
import { settle } from "../components/loadFailed.helpers";
import { ResumeScreen } from "./ResumeScreen";

vi.mock("../api/resume", async (importOriginal) => ({
  ...(await importOriginal<typeof resumeApi>()),
  getResume: vi.fn(),
  uploadResume: vi.fn(),
  pasteResume: vi.fn(),
  saveKeywords: vi.fn(),
  deleteResume: vi.fn(),
}));

const getResume = vi.mocked(resumeApi.getResume);
const uploadResume = vi.mocked(resumeApi.uploadResume);
const pasteResume = vi.mocked(resumeApi.pasteResume);
const saveKeywords = vi.mocked(resumeApi.saveKeywords);
const deleteResume = vi.mocked(resumeApi.deleteResume);

const resume = (extra: Partial<Resume> = {}): Resume =>
  ({
    id: "r1",
    name: "CV",
    fileName: "cv.pdf",
    revision: 3,
    updatedAt: "2026-11-02T10:00:00Z",
    keywords: ["react", "typescript"],
    years: 5,
    hasOriginal: true,
    text: "My resume text",
    ...extra,
  }) as Resume;

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

const mount = async (current: Resume | null = resume()) => {
  getResume.mockResolvedValue(current);
  await act(async () => {
    render(<ResumeScreen />, host);
  });
  await settle(4);
};

const button = (label: string) =>
  Array.from(host.querySelectorAll<HTMLButtonElement>("button")).find((b) => b.textContent?.trim() === label) as HTMLButtonElement;

const click = async (el: HTMLElement) => {
  await act(async () => {
    el.click();
  });
  await settle(4);
};

const type = async (el: HTMLInputElement | HTMLTextAreaElement, value: string) => {
  await act(async () => {
    el.value = value;
    el.dispatchEvent(new Event("input", { bubbles: true }));
  });
};

const pickFile = async (file: File) => {
  const input = host.querySelector<HTMLInputElement>('input[aria-label="Resume file"]')!;
  Object.defineProperty(input, "files", { value: [file], configurable: true });
  await act(async () => {
    input.dispatchEvent(new Event("change", { bubbles: true }));
  });
  await settle(4);
};

describe("ResumeScreen", () => {
  it("shows the stored resume with its keywords, experience and original", async () => {
    await mount();

    expect(host.textContent).toContain("Replace the resume");
    expect(host.textContent).toContain("revision 3");
    expect(host.querySelector('[aria-label="Keywords"]')?.textContent).toContain("react");
    expect(host.textContent).toContain("about 5 years of experience");
    expect(host.querySelector('a[href="/api/resume/original"]')).not.toBeNull();
  });

  it("invites an upload when there is no resume yet", async () => {
    await mount(null);

    expect(host.textContent).toContain("Upload your resume");
    expect(host.querySelector('[aria-label="Keywords"]')).toBeNull();
  });

  it("says it could not load the resume", async () => {
    getResume.mockRejectedValue(new client.ApiError(500, "Server down"));
    await act(async () => {
      render(<ResumeScreen />, host);
    });
    await settle(4);

    expect(host.querySelector('[role="alert"]')?.textContent).toBe("Server down");
  });

  it("uploads a chosen file and reports it", async () => {
    await mount(null);
    uploadResume.mockResolvedValue(resume({ fileName: "new.pdf" }));

    const file = new File(["x"], "new.pdf");
    await pickFile(file);

    expect(uploadResume).toHaveBeenCalledWith(file);
    expect(host.querySelector('[role="status"]')?.textContent).toBe("Read new.pdf. Fit scores are being updated.");
  });

  it("reports a failed upload", async () => {
    await mount(null);
    uploadResume.mockRejectedValue(new Error("Too big"));

    await pickFile(new File(["x"], "big.pdf"));

    expect(host.querySelector('[role="alert"]')?.textContent).toBe("That did not work. Nothing was changed.");
  });

  it("saves pasted text and closes the paste form", async () => {
    await mount(null);
    pasteResume.mockResolvedValue(resume());

    await click(button("Paste text instead"));
    const area = host.querySelector<HTMLTextAreaElement>('textarea[aria-label="Resume text"]')!;
    expect(button("Save text").disabled).toBe(true);

    await type(area, "Pasted CV");
    await click(button("Save text"));

    expect(pasteResume).toHaveBeenCalledWith("Pasted resume", "Pasted CV");
    expect(host.querySelector('textarea[aria-label="Resume text"]')).toBeNull();
  });

  it("adds keywords typed with commas and saves them", async () => {
    await mount();
    saveKeywords.mockResolvedValue(resume({ keywords: ["react", "typescript", "go", "sql"] }));

    const add = host.querySelector<HTMLInputElement>('input[placeholder="Add keywords, separated by commas"]')!;
    expect(button("Add").disabled).toBe(true);
    await type(add, "go, sql");
    await click(button("Add"));

    expect(host.querySelector('[aria-label="Keywords"]')?.textContent).toContain("sql");
    await click(button("Save keywords"));
    expect(saveKeywords).toHaveBeenCalledWith(["react", "typescript", "go", "sql"]);
  });

  it("removes a keyword and keeps save off until the list differs", async () => {
    await mount();
    expect(button("Save keywords").disabled).toBe(true);

    await click(host.querySelector<HTMLButtonElement>('button[aria-label="Remove react"]')!);

    expect(host.querySelector('[aria-label="Keywords"]')?.textContent).not.toContain("react");
    expect(button("Save keywords").disabled).toBe(false);
  });

  it("shows the text read from the resume", async () => {
    await mount();
    expect(host.querySelector(".resume-text")?.textContent).toBe("My resume text");
  });

  it("deletes the resume only after confirmation", async () => {
    await mount();
    deleteResume.mockResolvedValue(undefined);

    vi.spyOn(window, "confirm").mockReturnValueOnce(false);
    await click(button("Delete resume"));
    expect(deleteResume).not.toHaveBeenCalled();

    vi.spyOn(window, "confirm").mockReturnValueOnce(true);
    await click(button("Delete resume"));
    expect(deleteResume).toHaveBeenCalledTimes(1);
    expect(host.textContent).toContain("Upload your resume");
  });
});
