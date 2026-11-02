import { describe, expect, it } from "vitest";
import { nextTab } from "./tabs";

describe("nextTab", () => {
  it("moves with the arrows and wraps at the ends", () => {
    expect(nextTab("ArrowRight", 0, 3)).toBe(1);
    expect(nextTab("ArrowRight", 2, 3)).toBe(0);
    expect(nextTab("ArrowLeft", 0, 3)).toBe(2);
    expect(nextTab("ArrowDown", 1, 3)).toBe(2);
    expect(nextTab("ArrowUp", 1, 3)).toBe(0);
  });

  it("jumps to the ends with Home and End, and ignores other keys", () => {
    expect(nextTab("Home", 2, 3)).toBe(0);
    expect(nextTab("End", 0, 3)).toBe(2);
    expect(nextTab("Enter", 1, 3)).toBeNull();
  });
});
