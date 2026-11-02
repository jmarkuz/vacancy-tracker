/** Keyboard triage for the Inbox (T-048). Pure functions, so the rules are testable without a browser. */
export type TriageAction = "next" | "previous" | "save" | "dismiss" | "open-original" | "open-details";

const KEYS: Record<string, TriageAction> = {
  j: "next",
  k: "previous",
  s: "save",
  x: "dismiss",
  o: "open-original",
  Enter: "open-details",
};

/** Maps a key to an action. Modified keys (Ctrl, Cmd, Alt) are left to the browser. */
export function actionForKey(key: string, mods: { ctrl: boolean; meta: boolean; alt: boolean }): TriageAction | null {
  if (mods.ctrl || mods.meta || mods.alt) {
    return null;
  }
  return KEYS[key.length === 1 ? key.toLowerCase() : key] ?? null;
}

/** Moves the selection and keeps it inside the list. An empty list selects nothing (-1). */
export function moveSelection(current: number, delta: number, length: number): number {
  if (length === 0) {
    return -1;
  }
  const next = current < 0 ? 0 : current + delta;
  return Math.min(length - 1, Math.max(0, next));
}

/** After a row is removed, selection stays on the same position, or on the new last row. */
export function selectionAfterRemoval(index: number, newLength: number): number {
  if (newLength === 0) {
    return -1;
  }
  return Math.min(index, newLength - 1);
}

/**
 * True when a shortcut should leave the key alone: form fields, controls that act on Enter or Space (buttons,
 * links), and anything inside a dialog.
 */
export function isTypingTarget(target: EventTarget | null): boolean {
  if (!(target instanceof HTMLElement)) {
    return false;
  }
  const tag = target.tagName;
  return (
    tag === "INPUT" ||
    tag === "TEXTAREA" ||
    tag === "SELECT" ||
    tag === "BUTTON" ||
    tag === "A" ||
    target.isContentEditable === true ||
    target.closest("[role='dialog']") !== null
  );
}
