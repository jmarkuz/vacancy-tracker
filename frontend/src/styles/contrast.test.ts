import { describe, expect, it } from "vitest";
import css from "./tokens.css?raw";

/**
 * Beacon's contrast targets (ADR-052): text at least 7:1 on every surface it sits on, borders and the focus ring at
 * least 3:1, in both themes. The values are read from tokens.css itself, so a changed token is checked at once.
 */

function block(selector: string): Record<string, string> {
  const start = css.indexOf(`${selector} {`);
  const body = css.slice(start, css.indexOf("\n}", start));
  const out: Record<string, string> = {};
  for (const m of body.matchAll(/--([\w-]+):\s*(#[0-9a-fA-F]{6})\s*;/g)) {
    out[m[1]] = m[2];
  }
  return out;
}

const light = block(":root");
const dark = { ...light, ...block(':root[data-theme="dark"]') };

function luminance(hex: string): number {
  const [r, g, b] = [1, 3, 5].map((i) => {
    const c = parseInt(hex.slice(i, i + 2), 16) / 255;
    return c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
  });
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}

function ratio(a: string, b: string): number {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x);
  return (hi + 0.05) / (lo + 0.05);
}

const ROLES = ["blue", "green", "purple", "amber"];

describe.each([
  ["light", light],
  ["dark", dark],
])("%s theme", (_name, t) => {
  const pairs: [string, string, number][] = [
    ["color-text", "color-bg", 7],
    ["color-text", "color-surface", 7],
    ["color-text-muted", "color-bg", 7],
    ["color-text-muted", "color-surface", 7],
    ["color-danger", "color-surface", 7],
    ["color-border", "color-surface", 3],
    ["color-border", "color-bg", 3],
    ["color-focus", "color-bg", 3],
    ["color-focus", "color-surface", 3],
  ];
  for (const role of ROLES) {
    pairs.push([`role-${role}`, "color-surface", 7]);
    pairs.push([`role-${role}`, "color-bg", 7]);
    pairs.push([`role-${role}`, `role-${role}-soft`, 7]);
    pairs.push(["color-text", `role-${role}-soft`, 7]);
    pairs.push(["color-on-role", `role-${role}`, 7]);
  }

  it.each(pairs)("%s on %s is at least %d:1", (fg, bg, min) => {
    expect(t[fg], fg).toMatch(/^#/);
    expect(t[bg], bg).toMatch(/^#/);
    expect(ratio(t[fg], t[bg])).toBeGreaterThanOrEqual(min);
  });
});
