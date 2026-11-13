import { render } from "preact";
import { act } from "preact/test-utils";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import * as aiApi from "../api/ai";
import { ApplyPanel } from "./ApplyPanel";
import { settle } from "./loadFailed.helpers";

vi.mock("../api/ai", async (importOriginal) => ({
  ...(await importOriginal<typeof aiApi>()),
  listDocuments: vi.fn(),
  listPackages: vi.fn(),
}));
const listDocuments = vi.mocked(aiApi.listDocuments);
const listPackages = vi.mocked(aiApi.listPackages);

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

const show = async () => {
  await act(async () => {
    render(<ApplyPanel vacancyId="v1" vacancyUrl="https://jobs.example/1" docsVersion={0} onSubmitted={async () => undefined} />, host);
  });
  await settle(4);
};

describe("ApplyPanel", () => {
  it("shows no error when the documents and packages load", async () => {
    listDocuments.mockResolvedValue([]);
    listPackages.mockResolvedValue([]);

    await show();

    expect(host.textContent).toContain("Apply");
    expect(host.querySelector('[role="alert"]')).toBeNull();
  });

  it("says it could not load when the documents fail, and loads both again on Try again", async () => {
    listDocuments.mockRejectedValueOnce(new Error("offline"));
    listPackages.mockResolvedValue([]);
    await show();
    expect(host.querySelector('[role="alert"]')?.textContent).toContain("Could not load the documents and packages for this vacancy.");

    listDocuments.mockResolvedValue([]);
    await act(async () => {
      (host.querySelector('[role="alert"] button') as HTMLButtonElement).click();
    });
    await settle(4);

    expect(host.querySelector('[role="alert"]')).toBeNull();
    expect(listDocuments).toHaveBeenCalledTimes(2);
    expect(listPackages).toHaveBeenCalledTimes(2);
  });

  it("says it could not load when only the packages fail", async () => {
    listDocuments.mockResolvedValue([]);
    listPackages.mockRejectedValue(new Error("offline"));

    await show();

    expect(host.querySelector('[role="alert"]')).not.toBeNull();
  });
});
