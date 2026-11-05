import { describe, expect, it } from "vitest";

/**
 * The build joins all style files into one before it minifies. A rule left open at the end of one file therefore
 * swallows every rule that follows it (they become nested inside it and stop applying), although each file on its
 * own, as the dev server serves them, still looks fine. This test reads every style file and checks that its braces
 * balance, so such a slip fails here and not only in the production build.
 */
const files = import.meta.glob("./*.css", { query: "?raw", import: "default", eager: true }) as Record<string, string>;

/** Depth after reading the text, with comments and strings left out; also the first line where it goes below zero. */
function balance(css: string): { depth: number; underflowAt: number | null } {
  const text = css.replace(/\/\*[\s\S]*?\*\//g, (m) => m.replace(/[^\n]/g, " ")).replace(/"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'/g, (m) => m.replace(/[^\n]/g, " "));
  let depth = 0;
  let line = 1;
  let underflowAt: number | null = null;
  for (const ch of text) {
    if (ch === "\n") {
      line += 1;
    } else if (ch === "{") {
      depth += 1;
    } else if (ch === "}") {
      depth -= 1;
      if (depth < 0 && underflowAt === null) {
        underflowAt = line;
      }
    }
  }
  return { depth, underflowAt };
}

describe("style files", () => {
  it("are found", () => {
    expect(Object.keys(files).length).toBeGreaterThanOrEqual(4);
  });

  it.each(Object.entries(files))("%s closes every rule it opens", (name, css) => {
    const { depth, underflowAt } = balance(css);
    expect(underflowAt, `${name}: a } with nothing open, at line ${underflowAt}`).toBeNull();
    expect(depth, `${name}: ${depth} rule(s) left open at the end of the file`).toBe(0);
  });

  it("the balance check itself sees an open rule and a stray brace", () => {
    expect(balance(".a { color: red;").depth).toBe(1);
    expect(balance(".a { color: red; } }").underflowAt).toBe(1);
    expect(balance("/* { */ .a { content: \"}\"; }").depth).toBe(0);
    expect(balance(".a {\n.b {\n}\n}\n").depth).toBe(0);
  });
});
