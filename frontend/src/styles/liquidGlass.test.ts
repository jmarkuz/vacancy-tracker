import { describe, expect, it } from "vitest";
import css from "./tokens.css?raw";

/**
 * translucent glass keeps Beacon's text contrast (7:1). A glass fill is translucent, so its contrast depends on what it
 * lies on: here every fill is composited over the plain canvas and over the strongest ambient colour of the page
 * (the canvas with a pool of one role colour at its full strength), and the text tokens are checked on the result.
 * The values are read from tokens.css itself, so a changed token is checked at once.
 */

type Rgba = [number, number, number, number];

function blockAt(start: number): string {
  return css.slice(start, css.indexOf("\n}", start));
}

function token(body: string, name: string): string {
  const m = body.match(new RegExp(`--${name}:\\s*([^;]+);`));
  if (!m) {
    throw new Error(`token ${name} not found`);
  }
  return m[1].trim();
}

function parse(value: string): Rgba {
  const hex = value.match(/^#([0-9a-f]{6})$/i);
  if (hex) {
    const n = parseInt(hex[1], 16);
    return [(n >> 16) & 255, (n >> 8) & 255, n & 255, 1];
  }
  const m = value.match(/^rgb\(\s*(\d+)\s+(\d+)\s+(\d+)\s*(?:\/\s*([\d.]+))?\s*\)$/);
  if (!m) {
    throw new Error(`cannot read colour ${value}`);
  }
  return [Number(m[1]), Number(m[2]), Number(m[3]), m[4] === undefined ? 1 : Number(m[4])];
}

/** `top` over `bottom`, as the screen shows them. */
function over(top: Rgba, bottom: Rgba): Rgba {
  const a = top[3] + bottom[3] * (1 - top[3]);
  const c = (i: number) => (top[i] * top[3] + bottom[i] * bottom[3] * (1 - top[3])) / a;
  return [c(0), c(1), c(2), a];
}

function luminance([r, g, b]: Rgba): number {
  const f = (v: number) => {
    const c = v / 255;
    return c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
  };
  return 0.2126 * f(r) + 0.7152 * f(g) + 0.0722 * f(b);
}

function ratio(a: Rgba, b: Rgba): number {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x);
  return (hi + 0.05) / (lo + 0.05);
}

const lightBase = blockAt(css.indexOf(":root {"));
const darkBase = blockAt(css.indexOf(':root[data-theme="dark"] {'));
// The translucent glass tokens are in their own blocks, the last :root and the last dark block of the file.
const lightLg = blockAt(css.lastIndexOf(":root {", css.indexOf("--lg-filter:")));
const darkLg = blockAt(css.lastIndexOf(':root[data-theme="dark"] {'));

/** Strongest ambient pools: the role colours at the opacity the canvas uses them. */
const AMBIENT = {
  light: [
    [18, 67, 178, 0.11],
    [92, 43, 152, 0.1],
    [19, 86, 60, 0.09],
    [112, 68, 0, 0.07],
  ] as Rgba[],
  dark: [
    [195, 217, 243, 0.15],
    [217, 194, 255, 0.13],
    [168, 224, 196, 0.11],
    [240, 205, 134, 0.09],
  ] as Rgba[],
};

describe.each([
  ["light", lightBase, lightLg, AMBIENT.light],
  ["dark", darkBase, darkLg, AMBIENT.dark],
])("translucent glass, %s theme", (name, base, lg, ambient) => {
  const bg = parse(token(base, "color-bg"));
  const text = parse(token(base, "color-text"));
  const muted = parse(token(base, "color-text-muted"));
  const pools = ambient.map((a) => over(a, bg));
  const canvases = [bg, ...pools];
  const fills = ["lg-fill", "lg-fill-hover", "lg-fill-strong", "lg-fill-thin", "lg-fill-input"];

  it.each(fills)(`keeps the text at 7:1 on %s (${name}), over the canvas and over its ambient colour`, (fill) => {
    const value = parse(token(lg, fill));
    for (const canvas of canvases) {
      const onGlass = over(value, canvas);
      expect(ratio(text, onGlass)).toBeGreaterThanOrEqual(7);
      expect(ratio(muted, onGlass)).toBeGreaterThanOrEqual(7);
    }
  });

  it("keeps the tinted (ink) control readable: the label on the ink fill", () => {
    const ink = over(parse(token(lg, "lg-ink-fill")), bg);
    const on = parse(token(base, "color-on-ink"));
    expect(ratio(on, ink)).toBeGreaterThanOrEqual(7);
  });

  it("has a solid fallback for Reduced transparency that the text reads on", () => {
    const solid = parse(token(lg, "lg-solid"));
    expect(solid[3]).toBe(1);
    expect(ratio(text, solid)).toBeGreaterThanOrEqual(7);
  });
});

describe("translucent glass tokens", () => {
  it("are the same in the explicit dark theme and in 'follow the system' dark", () => {
    const system = blockAt(css.indexOf(':root[data-theme="system"] {', css.indexOf("--lg-filter:")));
    const names = [...darkLg.matchAll(/--(lg-[\w-]+):/g)].map((m) => m[1]);
    expect(names.length).toBeGreaterThan(10);
    for (const name of names) {
      const strip = (b: string) => token(b, name).replace(/\s+/g, " ");
      expect(strip(system), name).toBe(strip(darkLg));
    }
  });
});
