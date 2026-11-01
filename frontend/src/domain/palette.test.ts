import { describe, expect, it } from "vitest";
import { GO_TO, isPaletteShortcut, matchCommands, splitSnippet, stepSelection } from "./palette";

const key = (k: string, mods: Partial<{ ctrlKey: boolean; metaKey: boolean; altKey: boolean; shiftKey: boolean }> = {}) =>
  ({ key: k, ctrlKey: false, metaKey: false, altKey: false, shiftKey: false, ...mods });

describe("isPaletteShortcut", () => {
  it("accepts Ctrl+K and Cmd+K in either case", () => {
    expect(isPaletteShortcut(key("k", { ctrlKey: true }))).toBe(true);
    expect(isPaletteShortcut(key("K", { metaKey: true }))).toBe(true);
  });

  it("ignores a bare K and other combinations", () => {
    expect(isPaletteShortcut(key("k"))).toBe(false);
    expect(isPaletteShortcut(key("j", { ctrlKey: true }))).toBe(false);
    expect(isPaletteShortcut(key("k", { ctrlKey: true, shiftKey: true }))).toBe(false);
    expect(isPaletteShortcut(key("k", { ctrlKey: true, altKey: true }))).toBe(false);
  });
});

describe("matchCommands", () => {
  it("lists every screen for empty text", () => {
    expect(matchCommands("")).toEqual(GO_TO);
  });

  it("needs every word and ignores case", () => {
    expect(matchCommands("PIPE").map((c) => c.id)).toEqual(["pipeline", "board"]);
    expect(matchCommands("pipe board").map((c) => c.id)).toEqual(["board"]);
    expect(matchCommands("nothing here")).toEqual([]);
  });
});

describe("splitSnippet", () => {
  it("marks the matched parts", () => {
    expect(splitSnippet("We use [[Kotlin]] daily")).toEqual([
      { text: "We use ", match: false }, { text: "Kotlin", match: true }, { text: " daily", match: false },
    ]);
  });

  it("handles a match at either end and plain text", () => {
    expect(splitSnippet("[[Go]] fast")).toEqual([{ text: "Go", match: true }, { text: " fast", match: false }]);
    expect(splitSnippet("plain")).toEqual([{ text: "plain", match: false }]);
    expect(splitSnippet("")).toEqual([]);
  });

  it("keeps HTML as text, never as markup", () => {
    expect(splitSnippet("<b>[[x]]</b>")[0]).toEqual({ text: "<b>", match: false });
  });
});

describe("stepSelection", () => {
  it("wraps in both directions", () => {
    expect(stepSelection(2, 1, 3)).toBe(0);
    expect(stepSelection(0, -1, 3)).toBe(2);
  });

  it("starts at the first row going down and the last going up", () => {
    expect(stepSelection(-1, 1, 3)).toBe(0);
    expect(stepSelection(-1, -1, 3)).toBe(2);
  });

  it("is -1 when there are no rows", () => {
    expect(stepSelection(0, 1, 0)).toBe(-1);
  });
});
