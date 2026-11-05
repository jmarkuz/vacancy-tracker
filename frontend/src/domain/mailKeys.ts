import { activatesOnKey, isFieldTarget } from "./triage";

/** Keyboard shortcuts of the Mail tab. Pure rules, so they are testable without the screen. */
export type MailKeyAction =
  | "next"
  | "previous"
  | "first"
  | "last"
  | "toggle-select"
  | "accept"
  | "dismiss"
  | "link"
  | "open-application"
  | "toggle-more"
  | "help"
  | "escape";

const KEYS: Record<string, MailKeyAction> = {
  j: "next",
  ArrowDown: "next",
  k: "previous",
  ArrowUp: "previous",
  Home: "first",
  End: "last",
  x: "toggle-select",
  " ": "toggle-select",
  a: "accept",
  d: "dismiss",
  l: "link",
  o: "open-application",
  Enter: "toggle-more",
  "?": "help",
  Escape: "escape",
};

/** The keys and what they do, for the help on the screen. */
export const MAIL_SHORTCUTS: ReadonlyArray<{ keys: string[]; does: string }> = [
  { keys: ["J", "↓"], does: "Next mail" },
  { keys: ["K", "↑"], does: "Previous mail" },
  { keys: ["Home", "End"], does: "First or last mail" },
  { keys: ["X", "Space"], does: "Select or unselect the mail" },
  { keys: ["A"], does: "Accept the suggested status" },
  { keys: ["D"], does: "Dismiss the suggestion" },
  { keys: ["L"], does: "Link to an application" },
  { keys: ["O"], does: "Open the linked application" },
  { keys: ["Enter"], does: "Show more or less of the text" },
  { keys: ["?"], does: "Show or hide this help" },
  { keys: ["Esc"], does: "Close this help" },
];

export interface MailKeyEvent {
  key: string;
  code?: string;
  ctrlKey: boolean;
  metaKey: boolean;
  altKey: boolean;
  defaultPrevented?: boolean;
  target: EventTarget | null;
}

/**
 * Maps a key to an action. `code` is the physical key (KeyJ): with a Ukrainian or Russian layout `key` is "о" for J,
 * so the letter is then taken from the position, as in the Inbox.
 */
export function mailKeyAction(key: string, code?: string): MailKeyAction | null {
  const byKey = KEYS[key.length === 1 ? key.toLowerCase() : key];
  if (byKey) {
    return byKey;
  }
  const letter = /^Key([A-Z])$/.exec(code ?? "");
  return letter && !/^[a-z]$/i.test(key) ? (KEYS[letter[1].toLowerCase()] ?? null) : null;
}

/** True while a dialog (the confirmation, the command palette) is open: the page behind it must not react to keys. */
export function modalIsOpen(doc: Document): boolean {
  return doc.querySelector("[aria-modal='true']") !== null;
}

/**
 * The action for a key press on the Mail tab, or null when the key is not ours: Ctrl, Cmd or Alt held (Ctrl+K opens
 * the palette), typing in a field, anything behind an open dialog, a key a focused button or link uses itself
 * (Enter, Space), or an event someone already handled.
 */
export function mailKeyFromEvent(e: MailKeyEvent, doc: Document = document): MailKeyAction | null {
  if (e.ctrlKey || e.metaKey || e.altKey || e.defaultPrevented) {
    return null;
  }
  if (isFieldTarget(e.target) || modalIsOpen(doc) || activatesOnKey(e.target, e.key)) {
    return null;
  }
  return mailKeyAction(e.key, e.code);
}
