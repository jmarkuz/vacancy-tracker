import { render } from "preact";
import { act } from "preact/test-utils";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import * as analysisApi from "../api/analysis";
import type { Analysis } from "../api/analysis";
import * as client from "../api/client";
import { settle } from "./loadFailed.helpers";
import { AnalysisPanel } from "./AnalysisPanel";

vi.mock("../api/analysis", async (importOriginal) => ({
  ...(await importOriginal<typeof analysisApi>()),
  getAnalysis: vi.fn(),
  runAnalysis: vi.fn(),
}));

const getAnalysis = vi.mocked(analysisApi.getAnalysis);
const runAnalysis = vi.mocked(analysisApi.runAnalysis);

const quickFit = {
  vacancyId: "v1",
  resumeId: "r1",
  resumeRevision: 1,
  score: 62,
  matched: ["react"],
  missing: ["go"],
  reasons: [{ kind: "matched", text: "Knows react" }],
  computedAt: "2026-11-02T10:00:00Z",
};

const base: Analysis = {
  aiEnabled: false,
  quickFit: null,
  checklistState: "none",
  checklist: null,
  goalState: "none",
  goal: null,
  baseResume: null,
  baseCoverLetter: null,
  resume: null,
  coverLetter: null,
};

const employer = {
  source: "version" as const,
  documentId: "d1",
  version: 2,
  match: {
    score: 80,
    items: [{ requirementId: "q1", text: "Ships features", priority: "must", status: "covered", quote: "I shipped X", note: null }],
  },
};

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

const mount = async (vacancyId = "v1", presetId: string | null = null) => {
  await act(async () => {
    render(<AnalysisPanel vacancyId={vacancyId} presetId={presetId} docsVersion={0} />, host);
  });
  await settle(5);
};

describe("AnalysisPanel", () => {
  it("shows the quick estimate with its reasons and offers AI when it is off", async () => {
    getAnalysis.mockResolvedValue({ ...base, quickFit: quickFit as never });
    await mount();

    expect(host.querySelector(".quick-fit")?.textContent).toContain("a quick estimate from your resume keywords");
    expect(host.querySelector(".reasons")?.textContent).toContain("Knows react");
    expect(host.querySelector('a[href="#/settings"]')).not.toBeNull();
    expect(runAnalysis).not.toHaveBeenCalled();
  });

  it("asks for a resume when there is no quick estimate", async () => {
    getAnalysis.mockResolvedValue(base);
    await mount();

    expect(host.querySelector(".quick-fit")?.textContent).toContain("upload your resume to see a quick estimate.");
    expect(host.querySelector(".reasons")).toBeNull();
  });

  it("runs the AI analysis and shows the employer and goal matches", async () => {
    getAnalysis.mockResolvedValue({
      ...base,
      aiEnabled: true,
      checklistState: "current",
      checklist: { coreProblem: "Ship faster", requirements: [] },
      goalState: "current",
      goal: {
        score: 55,
        parameters: [{ name: "Salary", weight: 1, share: 0.5, score: 40, explanation: "Below target" }],
        dealBreaker: false,
        dealBreakerReason: null,
      },
      resume: employer,
    } as never);
    runAnalysis.mockResolvedValue({
      ...base,
      aiEnabled: true,
      checklistState: "current",
      checklist: { coreProblem: "Ship faster", requirements: [] },
      goalState: "current",
      goal: {
        score: 55,
        parameters: [{ name: "Salary", weight: 1, share: 0.5, score: 40, explanation: "Below target" }],
        dealBreaker: false,
        dealBreakerReason: null,
      },
      resume: employer,
    } as never);
    await mount("v1", "p1");

    expect(runAnalysis).toHaveBeenCalledWith("v1", "p1");
    expect(host.querySelector('[role="meter"]')?.getAttribute("aria-valuenow")).toBe("80");
    expect(host.textContent).toContain("Employer match, resume v2");
    expect(host.textContent).toContain("Core problem of this hire: Ship faster");
    expect(host.querySelector(".req-status")?.textContent).toBe("Covered");
    expect(host.querySelector(".req-quote")?.textContent).toBe("I shipped X");
    expect(host.textContent).toContain("Salary");
    expect(host.textContent).toContain("Below target");
  });

  it("shows the deal-breaker and the stale-text warnings", async () => {
    const full: Analysis = {
      ...base,
      aiEnabled: true,
      checklistState: "stale",
      checklist: { coreProblem: "X", requirements: [] },
      goalState: "not_ready",
      goal: { score: 10, parameters: [], dealBreaker: true, dealBreakerReason: "Relocation required" },
      baseResume: { ...employer, source: "base", version: null } as never,
    };
    getAnalysis.mockResolvedValue(full);
    runAnalysis.mockResolvedValue(full);
    await mount();

    expect(host.textContent).toContain("The vacancy text changed");
    expect(host.querySelector('[role="alert"]')?.textContent).toContain("Relocation required");
    expect(host.textContent).toContain("Employer match, base resume");
    expect(host.querySelector('a[href="#/presets"]')).not.toBeNull();
  });

  it("asks for the base resume and the goal when they are missing", async () => {
    const empty: Analysis = {
      ...base,
      aiEnabled: true,
      checklistState: "none",
      checklist: { coreProblem: "Y", requirements: [] },
      goalState: "not_ready",
      goal: null,
    };
    getAnalysis.mockResolvedValue(empty);
    runAnalysis.mockResolvedValue(empty);
    await mount("v1", "p 2");

    expect(host.querySelector("h4")?.textContent).toBe("Employer match");
    expect(host.textContent).toContain("Fill in the base resume of the preset to see it.");
    expect(host.textContent).toContain("Fill in the main goal of the");
    expect(host.querySelector('a[href="#/presets?id=p%202"]')).not.toBeNull();
  });

  it("says when the analysis cannot be loaded", async () => {
    getAnalysis.mockRejectedValue(new client.ApiError(500, "AI is down"));
    await mount();

    expect(host.querySelector('[role="alert"]')?.textContent).toBe("AI is down");
  });

  it("uses a plain message for any other load failure", async () => {
    getAnalysis.mockRejectedValue(new Error("offline"));
    await mount();

    expect(host.querySelector('[role="alert"]')?.textContent).toBe("The analysis could not be loaded.");
  });
});
