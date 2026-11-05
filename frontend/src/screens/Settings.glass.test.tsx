import { render } from "preact";
import { act } from "preact/test-utils";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { Settings } from "./Settings";

vi.mock("../api/ai", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../api/ai")>()),
  getAiStatus: vi.fn(async () => ({ provider: "off", enabled: false, consentedAt: null, budget: null })),
}));

let host: HTMLElement;

beforeEach(async () => {
  window.localStorage.clear();
  document.documentElement.removeAttribute("data-glass");
  host = document.createElement("div");
  document.body.appendChild(host);
  await act(async () => {
    render(<Settings theme="light" onTheme={() => undefined} />, host);
  });
});

afterEach(() => {
  render(null, host);
  host.remove();
  document.documentElement.removeAttribute("data-glass");
  window.localStorage.clear();
});

const radio = (value: string) => [...host.querySelectorAll<HTMLInputElement>('input[name="glass"]')].find((i) => i.value === value) as HTMLInputElement;

describe("Settings: Glass effects", () => {
  it("offers on and off, with on chosen by default", () => {
    expect(radio("on").checked).toBe(true);
    expect(radio("off").checked).toBe(false);
    expect([...host.querySelectorAll("legend")].map((l) => l.textContent)).toContain("Glass effects");
  });

  it("turns the glass off at once, remembers it, and turns it on again", async () => {
    await act(async () => {
      radio("off").click();
    });
    expect(document.documentElement.getAttribute("data-glass")).toBe("off");
    expect(window.localStorage.getItem("vt-glass")).toBe("off");
    expect(radio("off").checked).toBe(true);

    await act(async () => {
      radio("on").click();
    });
    expect(document.documentElement.hasAttribute("data-glass")).toBe(false);
    expect(radio("on").checked).toBe(true);
  });

  it("says that the system's own settings also make the glass solid", () => {
    expect(host.textContent).toContain("reduced transparency or more contrast");
  });
});
