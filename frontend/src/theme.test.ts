import { afterEach, describe, expect, it, vi } from "vitest";
import { applyGlass, applyTextSize, applyTheme, nextTheme, readGlass, readTextSize, readTheme } from "./theme";

afterEach(() => {
  vi.restoreAllMocks();
  document.documentElement.removeAttribute("data-theme");
  document.documentElement.removeAttribute("data-text-size");
  document.documentElement.removeAttribute("data-glass");
  window.localStorage.clear();
});

describe("theme", () => {
  it("is light when nothing was chosen (Beacon)", () => {
    expect(readTheme()).toBe("light");
  });

  it("cycles light, dark, system", () => {
    expect(nextTheme("light")).toBe("dark");
    expect(nextTheme("dark")).toBe("system");
    expect(nextTheme("system")).toBe("light");
  });

  it("stores and restores a manual choice", () => {
    applyTheme("dark");
    expect(document.documentElement.getAttribute("data-theme")).toBe("dark");
    expect(readTheme()).toBe("dark");
  });

  it("marks system explicitly so the tokens can follow the system preference", () => {
    applyTheme("system");
    expect(document.documentElement.getAttribute("data-theme")).toBe("system");
    expect(readTheme()).toBe("system");
  });

  it("ignores unknown stored values", () => {
    window.localStorage.setItem("vt-theme", "neon");
    expect(readTheme()).toBe("light");
  });

  it("still applies the theme when storage is blocked", () => {
    vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
      throw new Error("blocked");
    });
    vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => {
      throw new Error("blocked");
    });
    expect(() => applyTheme("dark")).not.toThrow();
    expect(document.documentElement.getAttribute("data-theme")).toBe("dark");
    expect(readTheme()).toBe("light");
  });
});

describe("text size", () => {
  it("is 18 px by default and can be raised to 20 or 22 px", () => {
    expect(readTextSize()).toBe("18");
    applyTextSize("22");
    expect(document.documentElement.getAttribute("data-text-size")).toBe("22");
    expect(readTextSize()).toBe("22");
    applyTextSize("18");
    expect(document.documentElement.hasAttribute("data-text-size")).toBe(false);
    expect(readTextSize()).toBe("18");
  });

  it("ignores unknown stored sizes", () => {
    window.localStorage.setItem("vt-text-size", "40");
    expect(readTextSize()).toBe("18");
  });
});

describe("glass effects", () => {
  it("are on when nothing was chosen, and the root carries no marker", () => {
    expect(readGlass()).toBe("on");
    applyGlass("on");
    expect(document.documentElement.hasAttribute("data-glass")).toBe(false);
  });

  it("can be switched off, which marks the root and is remembered", () => {
    applyGlass("off");

    expect(document.documentElement.getAttribute("data-glass")).toBe("off");
    expect(readGlass()).toBe("off");
  });

  it("come back on, and the marker goes", () => {
    applyGlass("off");
    applyGlass("on");

    expect(document.documentElement.hasAttribute("data-glass")).toBe(false);
    expect(readGlass()).toBe("on");
  });

  it("ignore an unknown stored value, and still apply when storage is blocked", () => {
    window.localStorage.setItem("vt-glass", "frosted");
    expect(readGlass()).toBe("on");

    vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
      throw new Error("blocked");
    });
    applyGlass("off");
    expect(document.documentElement.getAttribute("data-glass")).toBe("off");
  });
});
