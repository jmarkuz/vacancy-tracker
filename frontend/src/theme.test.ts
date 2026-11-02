import { afterEach, describe, expect, it, vi } from "vitest";
import { applyTheme, nextTheme, readTheme } from "./theme";

afterEach(() => {
  vi.restoreAllMocks();
  document.documentElement.removeAttribute("data-theme");
  window.localStorage.clear();
});

describe("theme", () => {
  it("is dark when nothing was chosen", () => {
    expect(readTheme()).toBe("dark");
  });

  it("cycles dark, light, system", () => {
    expect(nextTheme("dark")).toBe("light");
    expect(nextTheme("light")).toBe("system");
    expect(nextTheme("system")).toBe("dark");
  });

  it("stores and restores a manual choice", () => {
    applyTheme("light");
    expect(document.documentElement.getAttribute("data-theme")).toBe("light");
    expect(readTheme()).toBe("light");
  });

  it("marks system explicitly so the tokens can follow the system preference", () => {
    applyTheme("system");
    expect(document.documentElement.getAttribute("data-theme")).toBe("system");
    expect(readTheme()).toBe("system");
  });

  it("ignores unknown stored values", () => {
    window.localStorage.setItem("vt-theme", "neon");
    expect(readTheme()).toBe("dark");
  });

  it("still applies the theme when storage is blocked", () => {
    vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
      throw new Error("blocked");
    });
    vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => {
      throw new Error("blocked");
    });
    expect(() => applyTheme("light")).not.toThrow();
    expect(document.documentElement.getAttribute("data-theme")).toBe("light");
    expect(readTheme()).toBe("dark");
  });
});
