import { describe, expect, it } from "vitest";
import css from "./liquid-glass.css?raw";

/**
 * The navigation column is fixed on a wide screen only. The rule lives in one media block; a fixed sidebar outside it
 * would cover the page on a phone, and a page content without its explicit column would be placed under the panel.
 */
const wide = css.slice(css.indexOf("@media (min-width: 1001px)"), css.indexOf("\n}\n", css.indexOf("@media (min-width: 1001px)")) + 3);

describe("sidebar layout", () => {
  it("is fixed under the header on wide screens", () => {
    expect(wide).toContain(".sidebar {\n    position: fixed;");
    expect(wide).toContain("top: calc(var(--header-height) + var(--space-3));");
    expect(wide).toContain("bottom: var(--space-3);");
  });

  it("puts the page content in column 2, because a fixed panel no longer takes a column", () => {
    expect(wide).toContain(".main {\n    grid-column: 2;");
  });

  it("is the only place a sidebar is fixed (phones keep the bar in the flow of the page)", () => {
    const fixedAt = [...css.matchAll(/\.sidebar\s*\{[^}]*position:\s*fixed/g)].map((m) => m.index ?? -1);
    expect(fixedAt.length).toBe(1);
    expect(fixedAt[0]).toBeGreaterThan(css.indexOf("@media (min-width: 1001px)"));
  });
});
