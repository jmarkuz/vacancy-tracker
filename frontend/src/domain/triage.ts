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

/**
 * Maps a key to an action. Modified keys (Ctrl, Cmd, Alt) are left to the browser. `code` is the physical key
 * (KeyJ): with a Ukrainian or Russian layout `key` is "о" for J, so the letter is taken from the position instead.
 */
export function actionForKey(
  key: string,
  mods: { ctrl: boolean; meta: boolean; alt: boolean },
  code?: string,
): TriageAction | null {
  if (mods.ctrl || mods.meta || mods.alt) {
    return null;
  }
  const byKey = KEYS[key.length === 1 ? key.toLowerCase() : key];
  if (byKey) {
    return byKey;
  }
  const letter = /^Key([A-Z])$/.exec(code ?? "");
  // Only for keys that are not Latin letters themselves, so a layout like Dvorak keeps its own letters.
  return letter && !/^[a-z]$/i.test(key) ? (KEYS[letter[1].toLowerCase()] ?? null) : null;
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

/** True for a form field: a place where keys are text, or where Escape closes an open list. */
export function isFieldTarget(target: EventTarget | null): boolean {
  if (!(target instanceof HTMLElement)) {
    return false;
  }
  const tag = target.tagName;
  return tag === "INPUT" || tag === "TEXTAREA" || tag === "SELECT" || target.isContentEditable === true;
}

/** True when typing should not trigger shortcuts: form fields, or anything inside a dialog. */
export function isTypingTarget(target: EventTarget | null): boolean {
  return isFieldTarget(target) || (target instanceof HTMLElement && target.closest("[role='dialog']") !== null);
}

/** A focused button or link uses Enter and Space itself; a shortcut must not take those keys from it. */
export function activatesOnKey(target: EventTarget | null, key: string): boolean {
  if (!(target instanceof HTMLElement)) {
    return false;
  }
  return (key === "Enter" || key === " ") && (target.tagName === "BUTTON" || target.tagName === "A");
}
