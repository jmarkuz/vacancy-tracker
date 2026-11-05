import { afterEach, describe, expect, it } from "vitest";
import { MAIL_SHORTCUTS, type MailKeyEvent, mailKeyAction, mailKeyFromEvent, modalIsOpen } from "./mailKeys";

const ev = (key: string, patch: Partial<MailKeyEvent> = {}): MailKeyEvent => ({
  key,
  ctrlKey: false,
  metaKey: false,
  altKey: false,
  target: document.body,
  ...patch,
});

afterEach(() => {
  document.body.innerHTML = "";
});

describe("mailKeyAction", () => {
  it.each([
    ["j", "next"],
    ["J", "next"],
    ["ArrowDown", "next"],
    ["k", "previous"],
    ["ArrowUp", "previous"],
    ["Home", "first"],
    ["End", "last"],
    ["x", "toggle-select"],
    [" ", "toggle-select"],
    ["a", "accept"],
    ["d", "dismiss"],
    ["l", "link"],
    ["o", "open-application"],
    ["Enter", "toggle-more"],
    ["?", "help"],
    ["Escape", "escape"],
  ])("%s is %s", (key, action) => {
    expect(mailKeyAction(key)).toBe(action);
  });

  it("ignores other keys", () => {
    expect(mailKeyAction("q")).toBeNull();
    expect(mailKeyAction("Tab")).toBeNull();
  });

  it("reads the letter from the physical key on a non-Latin layout", () => {
    expect(mailKeyAction("о", "KeyJ")).toBe("next");
    expect(mailKeyAction("л", "KeyK")).toBe("previous");
    expect(mailKeyAction("ф", "KeyA")).toBe("accept");
  });

  it("keeps the letters of a Latin layout such as Dvorak", () => {
    expect(mailKeyAction("q", "KeyJ")).toBeNull();
    expect(mailKeyAction("с", "Digit1")).toBeNull();
  });

  it("every key of the help is a real shortcut", () => {
    const names: Record<string, string> = { "↓": "ArrowDown", "↑": "ArrowUp", Space: " ", Esc: "Escape" };
    for (const row of MAIL_SHORTCUTS) {
      for (const k of row.keys) {
        expect(mailKeyAction(names[k] ?? k), k).not.toBeNull();
      }
    }
  });
});

describe("mailKeyFromEvent", () => {
  it("maps a plain key", () => {
    expect(mailKeyFromEvent(ev("j"))).toBe("next");
  });

  it.each(["ctrlKey", "metaKey", "altKey"] as const)("leaves the key alone with %s held", (mod) => {
    expect(mailKeyFromEvent(ev("j", { [mod]: true }))).toBeNull();
    expect(mailKeyFromEvent(ev("k", { [mod]: true }))).toBeNull();
  });

  it("leaves an event someone handled already", () => {
    expect(mailKeyFromEvent(ev("j", { defaultPrevented: true }))).toBeNull();
  });

  it.each(["input", "textarea", "select"])("does not fire in a %s", (tag) => {
    const field = document.createElement(tag);
    document.body.appendChild(field);
    expect(mailKeyFromEvent(ev("j", { target: field }))).toBeNull();
    expect(mailKeyFromEvent(ev(" ", { target: field }))).toBeNull();
  });

  it("does not fire in an editable element", () => {
    const div = document.createElement("div");
    Object.defineProperty(div, "isContentEditable", { value: true });
    document.body.appendChild(div);
    expect(mailKeyFromEvent(ev("j", { target: div }))).toBeNull();
  });

  it("does not take Enter or Space from a focused button or link", () => {
    const button = document.createElement("button");
    const link = document.createElement("a");
    document.body.append(button, link);
    expect(mailKeyFromEvent(ev("Enter", { target: button }))).toBeNull();
    expect(mailKeyFromEvent(ev(" ", { target: link }))).toBeNull();
    expect(mailKeyFromEvent(ev("j", { target: button }))).toBe("next");
  });

  it("does not fire while a dialog is open", () => {
    expect(modalIsOpen(document)).toBe(false);
    const dialog = document.createElement("div");
    dialog.setAttribute("role", "alertdialog");
    dialog.setAttribute("aria-modal", "true");
    document.body.appendChild(dialog);
    expect(modalIsOpen(document)).toBe(true);
    expect(mailKeyFromEvent(ev("j"))).toBeNull();
    expect(mailKeyFromEvent(ev("Escape", { target: dialog }))).toBeNull();
  });
});
