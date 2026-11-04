import { render } from "preact";
import { act } from "preact/test-utils";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import type { Theme } from "../theme";
import { ThemeIcon } from "./ThemeIcon";

let host: HTMLElement;

beforeEach(() => {
  host = document.createElement("div");
  document.body.appendChild(host);
});

afterEach(() => {
  render(null, host);
  host.remove();
});

const icon = (theme: Theme) => {
  act(() => render(<ThemeIcon theme={theme} />, host));
  return host.querySelector("svg") as SVGElement;
};

describe("ThemeIcon", () => {
  it("draws a sun for light: a circle with rays", () => {
    const svg = icon("light");

    expect(svg.getAttribute("data-theme-icon")).toBe("light");
    expect(svg.querySelector("circle")).not.toBeNull();
    expect(svg.querySelectorAll("path")).toHaveLength(1);
  });

  it("draws a moon for dark: one crescent path and no circle", () => {
    const svg = icon("dark");

    expect(svg.getAttribute("data-theme-icon")).toBe("dark");
    expect(svg.querySelector("circle")).toBeNull();
    expect(svg.querySelectorAll("path")).toHaveLength(1);
  });

  it("draws a monitor for the system theme: a screen and a stand", () => {
    const svg = icon("system");

    expect(svg.getAttribute("data-theme-icon")).toBe("system");
    expect(svg.querySelector("rect")).not.toBeNull();
    expect(svg.querySelector("circle")).toBeNull();
  });

  it("is decorative and follows the text color", () => {
    const svg = icon("light");

    expect(svg.getAttribute("aria-hidden")).toBe("true");
    expect(svg.getAttribute("focusable")).toBe("false");
    expect(svg.getAttribute("stroke")).toBe("currentColor");
  });

  it("looks different for each of the three themes", () => {
    const drawings = (["light", "dark", "system"] as Theme[]).map((t) => icon(t).innerHTML);

    expect(new Set(drawings).size).toBe(3);
  });
});
