import { render } from "preact";
import { act } from "preact/test-utils";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import * as profilesApi from "../api/profiles";
import type { Meta } from "../api/profiles";
import { settle } from "./loadFailed.helpers";
import { Wizard } from "./Wizard";

vi.mock("../api/profiles", async (importOriginal) => ({
  ...(await importOriginal<typeof profilesApi>()),
  createProfile: vi.fn(),
}));

const createProfile = vi.mocked(profilesApi.createProfile);
const meta = { fields: [], modes: [], missingValuePolicies: [], minWeight: 0, maxWeight: 10, defaultThresholds: {} } as unknown as Meta;

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

const mount = async (onCreated = vi.fn(), onSkip = vi.fn()) => {
  await act(async () => {
    render(<Wizard meta={meta} onCreated={onCreated} onSkip={onSkip} />, host);
  });
  return { onCreated, onSkip };
};

const type = async (label: string, value: string) => {
  await act(async () => {
    const el = Array.from(host.querySelectorAll<HTMLInputElement>("input[type=text]")).find((i) => i.closest("label")?.textContent?.includes(label))!;
    el.value = value;
    el.dispatchEvent(new Event("input", { bubbles: true }));
  });
};

const submit = async () => {
  await act(async () => {
    host.querySelector<HTMLFormElement>("form")!.dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }));
  });
  await settle(3);
};

describe("Wizard", () => {
  it("shows what is missing and does not create a profile yet", async () => {
    await mount();

    await submit();

    expect(createProfile).not.toHaveBeenCalled();
    expect(host.querySelectorAll('.field-error[role="alert"]').length).toBeGreaterThan(0);
  });

  it("creates the profile from the answers and hands it on", async () => {
    const created = { id: "s1", name: "Frontend" };
    createProfile.mockResolvedValue(created as never);
    const { onCreated } = await mount();

    await type("Name this profile", "Frontend");
    await type("What are you looking for", "react, typescript");
    await submit();

    expect(createProfile).toHaveBeenCalledTimes(1);
    expect(onCreated).toHaveBeenCalledWith(created);
  });

  it("toggles work modes and sends them with the profile", async () => {
    createProfile.mockResolvedValue({ id: "s1" } as never);
    await mount();

    const remote = Array.from(host.querySelectorAll<HTMLInputElement>('input[type="checkbox"]')).find((c) => c.parentElement?.textContent?.trim() === "remote")!;
    await act(async () => {
      remote.checked = true;
      remote.dispatchEvent(new Event("change", { bubbles: true }));
    });
    await act(async () => {
      remote.checked = false;
      remote.dispatchEvent(new Event("change", { bubbles: true }));
    });
    await act(async () => {
      remote.checked = true;
      remote.dispatchEvent(new Event("change", { bubbles: true }));
    });
    expect(remote.checked).toBe(true);
  });

  it("explains a name that already exists", async () => {
    createProfile.mockRejectedValue(new profilesApi.ProfileRejected(409, {}, "dup"));
    const { onCreated } = await mount();

    await type("Name this profile", "Frontend");
    await type("What are you looking for", "react");
    await submit();

    expect(host.querySelector('[role="alert"]')?.textContent).toBe("A profile with this name already exists.");
    expect(onCreated).not.toHaveBeenCalled();
  });

  it("shows the server's own words when it refuses the answers", async () => {
    createProfile.mockRejectedValue(new profilesApi.ProfileRejected(422, { salary: "Amount is too big", currency: "Unknown currency" }, "refused"));
    await mount();

    await type("Name this profile", "Frontend");
    await type("What are you looking for", "react");
    await submit();

    expect(host.querySelector('[role="alert"]')?.textContent).toBe("Amount is too big Unknown currency");
  });

  it("falls back to a plain message when the save fails for another reason", async () => {
    createProfile.mockRejectedValue(new Error("network"));
    await mount();

    await type("Name this profile", "Frontend");
    await type("What are you looking for", "react");
    await submit();

    expect(host.querySelector('[role="alert"]')?.textContent).toBe("The profile was not saved. Try again.");
  });

  it("skips to the hand-built editor", async () => {
    const { onSkip } = await mount();

    await act(async () => {
      Array.from(host.querySelectorAll("button")).find((b) => b.textContent?.includes("Skip"))!.click();
    });

    expect(onSkip).toHaveBeenCalledTimes(1);
  });
});
