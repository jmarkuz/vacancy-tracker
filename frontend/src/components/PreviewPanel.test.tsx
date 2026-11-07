import { render } from "preact";
import { act } from "preact/test-utils";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import * as profilesApi from "../api/profiles";
import type { ProfileInput } from "../api/profiles";
import { settle } from "./loadFailed.helpers";
import { PreviewPanel } from "./PreviewPanel";

vi.mock("../api/profiles", async (importOriginal) => ({ ...(await importOriginal<typeof profilesApi>()), previewProfile: vi.fn() }));
const previewProfile = vi.mocked(profilesApi.previewProfile);
const draft = { name: "P", sources: [], criteria: [] } as unknown as ProfileInput;
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

const mount = async (valid: boolean, profileId?: string | null) => {
  await act(async () => {
    render(<PreviewPanel draft={draft} valid={valid} profileId={profileId} />, host);
  });
};

const wait = async (ms: number) => {
  await act(async () => {
    await new Promise((r) => setTimeout(r, ms));
  });
  await settle(3);
};

describe("PreviewPanel", () => {
  it("asks for a fix instead of previewing an invalid draft", async () => {
    await mount(false);
    await wait(700);

    expect(previewProfile).not.toHaveBeenCalled();
    expect(host.textContent).toContain("Fix the marked fields to see how many vacancies");
  });

  it("shows the matches with their samples and reasons", async () => {
    previewProfile.mockResolvedValue({
      total: 3,
      applicable: 3,
      matched: 1,
      inbox: 1,
      filteredOut: 2,
      samples: [{ vacancyId: "v1", title: "React dev", company: "Acme", reasons: [{ criterionId: "c1", kind: "MATCH", text: "title has react" }] }],
    } as never);
    await mount(true, "s1");
    await wait(700);

    expect(previewProfile).toHaveBeenCalledWith(draft, expect.anything(), "s1");
    expect(host.textContent).toContain("React dev");
    expect(host.textContent).toContain("title has react");
    expect(host.querySelector('[role="status"]')?.textContent).toContain("Of 3 existing vacancies, 1 would match");
  });

  it("omits the sample list when nothing matches", async () => {
    previewProfile.mockResolvedValue({ total: 0, samples: [] } as never);
    await mount(true, null);
    await wait(700);

    expect(previewProfile).toHaveBeenCalledWith(draft, expect.anything(), undefined);
    expect(host.querySelector(".samples")).toBeNull();
  });

  it("says the preview could not be calculated when the server fails", async () => {
    previewProfile.mockRejectedValue(new Error("down"));
    await mount(true);
    await wait(700);

    expect(host.querySelector('[role="alert"]')?.textContent).toBe("The preview could not be calculated right now.");
  });
});
