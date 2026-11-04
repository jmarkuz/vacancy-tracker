import { render } from "preact";
import { act } from "preact/test-utils";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { GhostBadge, ghostReasons } from "./GhostBadge";

let host: HTMLElement;

beforeEach(() => {
  host = document.createElement("div");
  document.body.appendChild(host);
});

afterEach(() => {
  render(null, host);
  host.remove();
});

describe("GhostBadge", () => {
  it("shows the label with the reasons for a likely ghost", () => {
    act(() => render(<GhostBadge ghost={{ likely: true, signs: 2, reasons: ["Posted 45 days ago", "No salary stated"] }} />, host));

    const badge = host.querySelector(".ghost-badge");
    expect(badge?.textContent).toBe("Likely ghost");
    expect(badge?.getAttribute("title")).toBe("Posted 45 days ago; No salary stated");
    expect(badge?.getAttribute("aria-label")).toBe("Likely ghost: Posted 45 days ago; No salary stated");
  });

  it("shows nothing when the advice says not likely, or when there is no advice", () => {
    act(() => render(<GhostBadge ghost={{ likely: false, signs: 1, reasons: ["No salary stated"] }} />, host));
    expect(host.textContent).toBe("");

    act(() => render(<GhostBadge ghost={undefined} />, host));
    expect(host.textContent).toBe("");
  });
});

describe("ghostReasons", () => {
  it("joins the reasons for a likely ghost", () => {
    expect(ghostReasons({ likely: true, signs: 2, reasons: ["Posted 45 days ago", "No salary stated"] })).toBe("Posted 45 days ago · No salary stated");
  });

  it("is null when not likely, without reasons, or without advice", () => {
    expect(ghostReasons({ likely: false, signs: 1, reasons: ["No salary stated"] })).toBeNull();
    expect(ghostReasons({ likely: true, signs: 2, reasons: [] })).toBeNull();
    expect(ghostReasons(undefined)).toBeNull();
  });
});
