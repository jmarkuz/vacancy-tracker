import { describe, expect, it } from "vitest";
import css from "./liquid-glass.css?raw";

/**
 * The motion of translucent glass, checked on the file itself: every spring and press stops under "reduce motion", and the
 * light that follows the pointer keeps the text at 7:1.
 */

const reduced = css.slice(css.lastIndexOf("@media (prefers-reduced-motion: reduce)"));

function composite(top: [number, number, number, number], bottom: [number, number, number]): [number, number, number] {
  const [r, g, b, a] = top;
  return [r * a + bottom[0] * (1 - a), g * a + bottom[1] * (1 - a), b * a + bottom[2] * (1 - a)];
}

function luminance([r, g, b]: [number, number, number]): number {
  const f = (v: number) => {
    const c = v / 255;
    return c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
  };
  return 0.2126 * f(r) + 0.7152 * f(g) + 0.0722 * f(b);
}

function ratio(a: [number, number, number], b: [number, number, number]): number {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x);
  return (hi + 0.05) / (lo + 0.05);
}

describe("translucent glass motion, file level", () => {
  it("defines one spring and one quick press, used by the controls", () => {
    expect(css).toContain("--lg-ease-spring: cubic-bezier(0.25, 1.75, 0.45, 1)");
    expect(css).toContain("--lg-dur-press: 110ms");
    expect(css.match(/var\(--lg-ease-spring\)/g)?.length).toBeGreaterThanOrEqual(3);
  });

  it("stops every spring, sheet entrance and press under reduced motion", () => {
    expect(reduced).toContain(".drawer {\n    animation: none;");
    expect(reduced).toContain(".confirm-dialog,\n  .palette,\n  .drawer");
    expect(reduced).toMatch(/\.btn,\s*\.chip,\s*\.tab,\s*\.nav-item,[\s\S]*?transition: none !important/);
    expect(reduced).toContain(".nav-item:hover,");
    expect(reduced).toMatch(/\.btn:active:not\(:disabled\),[\s\S]*?transform: none !important/);
  });

  it("lifts the nav item only with motion allowed (the lift rule sits outside the reduced block)", () => {
    const lift = css.indexOf(".nav-item:hover {\n  transform: translateY(-1px);");
    expect(lift).toBeGreaterThan(0);
    expect(lift).toBeLessThan(css.indexOf("@media (prefers-reduced-motion: reduce)"));
  });

  it("follows the pointer through the --lg-x and --lg-y the script sets, with a centred fallback", () => {
    expect(css).toContain("radial-gradient(9rem 6rem at var(--lg-x, 50%) var(--lg-y, 50%)");
  });

  it("keeps the text at 7:1 under the pointer glow in both themes", () => {
    const glow: [number, number, number, number] = [255, 255, 255, 0.18];
    const lightCanvas: [number, number, number] = [245, 245, 247];
    const lightFill = composite([255, 255, 255, 0.55], lightCanvas);
    const lightText: [number, number, number] = [29, 29, 31];
    expect(ratio(lightText, composite(glow, lightFill))).toBeGreaterThanOrEqual(7);

    const darkCanvas: [number, number, number] = [0, 0, 0];
    const darkFill = composite([255, 255, 255, 0.07], darkCanvas);
    const darkText: [number, number, number] = [245, 245, 247];
    expect(ratio(darkText, composite(glow, darkFill))).toBeGreaterThanOrEqual(7);

    const ink = composite([29, 29, 31, 0.94], lightCanvas);
    expect(ratio([255, 255, 255], composite(glow, ink))).toBeGreaterThanOrEqual(7);
  });
});
