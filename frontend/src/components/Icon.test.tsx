import { render } from "preact";
import { act } from "preact/test-utils";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { Icon, type IconName } from "./Icon";

const NAMES: IconName[] = ["table", "board", "columns", "plus", "filter", "refresh", "chevron-left", "chevron-right", "bookmark", "save", "trash", "close", "check"];

let host: HTMLElement;

beforeEach(() => {
  host = document.createElement("div");
  document.body.appendChild(host);
});

afterEach(() => {
  render(null, host);
  host.remove();
});

const draw = (name: IconName, spin = false) => {
  act(() => render(<Icon name={name} spin={spin} />, host));
  return host.querySelector("svg") as SVGElement;
};

describe("Icon", () => {
  it("draws every icon of the set, each with something in it", () => {
    for (const name of NAMES) {
      const svg = draw(name);

      expect(svg.getAttribute("data-icon")).toBe(name);
      expect(svg.children.length, name).toBeGreaterThan(0);
    }
  });

  it("gives every icon its own drawing", () => {
    const drawings = NAMES.map((name) => draw(name).innerHTML);

    expect(new Set(drawings).size).toBe(NAMES.length);
  });

  it("is decorative, takes the text color and has a fixed stroke", () => {
    const svg = draw("plus");

    expect(svg.getAttribute("aria-hidden")).toBe("true");
    expect(svg.getAttribute("focusable")).toBe("false");
    expect(svg.getAttribute("stroke")).toBe("currentColor");
    expect(svg.getAttribute("stroke-width")).toBe("2");
  });

  it("turns only when asked to", () => {
    expect(draw("refresh").getAttribute("class")).toBe("icon");
    expect(draw("refresh", true).getAttribute("class")).toBe("icon icon-spin");
  });
});
