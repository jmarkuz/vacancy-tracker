import { render } from "preact";
import { act } from "preact/test-utils";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import * as aiApi from "../api/ai";
import * as presetsApi from "../api/presets";
import { DocumentsPanel } from "./DocumentsPanel";
import { settle } from "./loadFailed.helpers";

vi.mock("../api/ai", async (importOriginal) => ({
  ...(await importOriginal<typeof aiApi>()),
  listDocuments: vi.fn(),
  getAiStatus: vi.fn(),
}));
vi.mock("../api/presets", async (importOriginal) => ({ ...(await importOriginal<typeof presetsApi>()), listPresets: vi.fn() }));
const listDocuments = vi.mocked(aiApi.listDocuments);
const getAiStatus = vi.mocked(aiApi.getAiStatus);
const listPresets = vi.mocked(presetsApi.listPresets);

let host: HTMLElement;

beforeEach(() => {
  host = document.createElement("div");
  document.body.appendChild(host);
  getAiStatus.mockResolvedValue({ enabled: false, configured: false } as Awaited<ReturnType<typeof aiApi.getAiStatus>>);
  listPresets.mockResolvedValue([]);
  listDocuments.mockResolvedValue([]);
});

afterEach(() => {
  render(null, host);
  host.remove();
  vi.resetAllMocks();
});

const show = async () => {
  await act(async () => {
    render(<DocumentsPanel vacancyId="v1" title="Backend Engineer" company="Zeta Corp" presetId={null} onPresetChange={() => undefined} />, host);
  });
  await settle(4);
};

describe("DocumentsPanel", () => {
  it("shows no load error when everything loads", async () => {
    await show();

    expect(host.querySelector('[role="alert"]')).toBeNull();
  });

  it("says it could not load when the documents fail, and loads again on Try again", async () => {
    listDocuments.mockRejectedValueOnce(new Error("offline"));
    await show();
    expect(host.querySelector('[role="alert"]')?.textContent).toContain("Could not load the documents, the presets or the AI status.");

    await act(async () => {
      (host.querySelector('[role="alert"] button') as HTMLButtonElement).click();
    });
    await settle(4);

    expect(host.querySelector('[role="alert"]')).toBeNull();
    expect(listDocuments).toHaveBeenCalledTimes(2);
  });

  it("says it could not load when the presets or the AI status fail", async () => {
    listPresets.mockRejectedValue(new Error("offline"));

    await show();

    expect(host.querySelector('[role="alert"]')).not.toBeNull();
  });
});
