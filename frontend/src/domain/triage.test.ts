import { describe, expect, it } from "vitest";
import { actionForKey, isTypingTarget, moveSelection, selectionAfterRemoval } from "./triage";

const none = { ctrl: false, meta: false, alt: false };

describe("actionForKey", () => {
  it("maps the documented keys, case-insensitively", () => {
    expect(actionForKey("j", none)).toBe("next");
    expect(actionForKey("K", none)).toBe("previous");
    expect(actionForKey("s", none)).toBe("save");
    expect(actionForKey("X", none)).toBe("dismiss");
    expect(actionForKey("o", none)).toBe("open-original");
    expect(actionForKey("Enter", none)).toBe("open-details");
  });

  it("ignores other keys and modified keys", () => {
    expect(actionForKey("a", none)).toBeNull();
    expect(actionForKey("s", { ...none, ctrl: true })).toBeNull();
    expect(actionForKey("s", { ...none, meta: true })).toBeNull();
    expect(actionForKey("j", { ...none, alt: true })).toBeNull();
  });
});

describe("moveSelection", () => {
  it("stays inside the list", () => {
    expect(moveSelection(0, -1, 5)).toBe(0);
    expect(moveSelection(4, 1, 5)).toBe(4);
    expect(moveSelection(2, 1, 5)).toBe(3);
  });

  it("starts at the first row when nothing is selected", () => {
    expect(moveSelection(-1, 1, 3)).toBe(0);
  });

  it("selects nothing in an empty list", () => {
    expect(moveSelection(0, 1, 0)).toBe(-1);
  });
});

describe("selectionAfterRemoval", () => {
  it("keeps the position, or moves to the new last row", () => {
    expect(selectionAfterRemoval(2, 4)).toBe(2);
    expect(selectionAfterRemoval(3, 3)).toBe(2);
    expect(selectionAfterRemoval(0, 0)).toBe(-1);
  });
});

describe("isTypingTarget", () => {
  it("is true for form fields, buttons, links and dialogs only", () => {
    expect(isTypingTarget(document.createElement("input"))).toBe(true);
    expect(isTypingTarget(document.createElement("textarea"))).toBe(true);
    expect(isTypingTarget(document.createElement("select"))).toBe(true);
    expect(isTypingTarget(document.createElement("div"))).toBe(false);
    expect(isTypingTarget(document.createElement("button"))).toBe(true);
    expect(isTypingTarget(document.createElement("a"))).toBe(true);
    const dialog = document.createElement("div");
    dialog.setAttribute("role", "dialog");
    const inside = document.createElement("span");
    dialog.appendChild(inside);
    expect(isTypingTarget(inside)).toBe(true);
    expect(isTypingTarget(null)).toBe(false);
  });
});
