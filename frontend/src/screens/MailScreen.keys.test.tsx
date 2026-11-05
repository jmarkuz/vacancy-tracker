import { render } from "preact";
import { act } from "preact/test-utils";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import * as mailApi from "../api/mail";
import type { Mail, MailStatus } from "../api/mail";
import { MailScreen } from "./MailScreen";

vi.mock("../api/mail", async (importOriginal) => ({
  ...(await importOriginal<typeof mailApi>()),
  getMailStatus: vi.fn(),
  listMail: vi.fn(),
  acceptSuggestion: vi.fn(),
  dismissSuggestion: vi.fn(),
  listApplications: vi.fn(),
  deleteMail: vi.fn(),
}));

const listMail = vi.mocked(mailApi.listMail);
const getStatus = vi.mocked(mailApi.getMailStatus);
const accept = vi.mocked(mailApi.acceptSuggestion);
const dismiss = vi.mocked(mailApi.dismissSuggestion);
const listApplications = vi.mocked(mailApi.listApplications);

const LONG = Array.from({ length: 40 }, (_, i) => `Line number ${i} of a long message.`).join("\n\n");

const mail = (id: string, patch: Partial<Mail> = {}): Mail => ({
  id,
  mailKey: `k-${id}`,
  folder: "INBOX",
  sender: "jane@recruiting.example",
  senderName: "Jane",
  subject: `Subject ${id}`,
  receivedAt: "2026-11-03T10:00:00Z",
  snippet: "Hello",
  category: "recruiter_message",
  vacancyId: null,
  linkReason: null,
  suggestedStatus: null,
  suggestionState: "none",
  createdAt: "2026-11-03T10:00:00Z",
  jobs: [],
  ...patch,
});

const status: MailStatus = {
  configured: true,
  folders: ["INBOX"],
  pending: 0,
  lastSync: null,
  lastError: null,
  syncing: false,
  lastResult: null,
  intervalMinutes: 15,
  nextCheck: null,
};

let host: HTMLElement;

const cards = () => [...host.querySelectorAll<HTMLElement>(".mail-item")];
const currentIds = () => cards().filter((c) => c.getAttribute("aria-current") === "true").map((c) => c.id);
const checkboxes = () => [...host.querySelectorAll<HTMLInputElement>(".mail-head input[type=checkbox]")];

async function press(key: string, init: KeyboardEventInit = {}, target: EventTarget = document.body) {
  await act(async () => {
    target.dispatchEvent(new KeyboardEvent("keydown", { key, bubbles: true, cancelable: true, ...init }));
    await Promise.resolve();
  });
}

async function mount(items: Mail[]) {
  listMail.mockResolvedValue(items);
  getStatus.mockResolvedValue(status);
  await act(async () => {
    render(<MailScreen />, host);
  });
  await act(async () => {
    await Promise.resolve();
  });
}

beforeEach(() => {
  host = document.createElement("div");
  document.body.appendChild(host);
  accept.mockResolvedValue({ vacancyStatus: "Interview" } as never);
  dismiss.mockResolvedValue(undefined as never);
  listApplications.mockResolvedValue([{ id: "v1", title: "Java Dev", company: "Zeta", status: "Applied" }]);
});

afterEach(() => {
  render(null, host);
  host.remove();
  vi.resetAllMocks();
});

describe("Mail tab keyboard: the current mail", () => {
  it("starts without a current mail and has one tab stop on the first card", async () => {
    await mount([mail("a"), mail("b"), mail("c")]);
    expect(currentIds()).toEqual([]);
    expect(cards().map((c) => c.tabIndex)).toEqual([0, -1, -1]);
  });

  it("J and ArrowDown move forward, K and ArrowUp back, and stop at the ends", async () => {
    await mount([mail("a"), mail("b"), mail("c")]);
    await press("k");
    expect(currentIds()).toEqual(["mail-a"]);
    await press("j");
    expect(currentIds()).toEqual(["mail-b"]);
    await press("ArrowDown");
    await press("ArrowDown");
    expect(currentIds()).toEqual(["mail-c"]);
    await press("ArrowUp");
    expect(currentIds()).toEqual(["mail-b"]);
    await press("ArrowUp");
    await press("k");
    expect(currentIds()).toEqual(["mail-a"]);
  });

  it("Home and End jump to the first and the last mail", async () => {
    await mount([mail("a"), mail("b"), mail("c")]);
    await press("End");
    expect(currentIds()).toEqual(["mail-c"]);
    await press("Home");
    expect(currentIds()).toEqual(["mail-a"]);
  });

  it("marks one card with aria-current, moves the focus to it and keeps a roving tab stop", async () => {
    await mount([mail("a"), mail("b")]);
    await press("j");
    await press("j");
    const [a, b] = cards();
    expect(b.getAttribute("aria-current")).toBe("true");
    expect(a.hasAttribute("aria-current")).toBe(false);
    expect(b.classList.contains("is-current")).toBe(true);
    expect(document.activeElement).toBe(b);
    expect([a.tabIndex, b.tabIndex]).toEqual([-1, 0]);
  });

  it("focusing a button of a card makes that card current", async () => {
    await mount([mail("a"), mail("b")]);
    const button = cards()[1].querySelector("button") as HTMLButtonElement;
    await act(async () => {
      button.focus();
    });
    expect(currentIds()).toEqual(["mail-b"]);
  });

  it("scrolls the current card into view, without smoothing when motion is reduced", async () => {
    const scroll = vi.fn();
    Element.prototype.scrollIntoView = scroll;
    window.matchMedia = ((q: string) => ({ matches: q.includes("reduce") }) as MediaQueryList) as typeof window.matchMedia;
    try {
      await mount([mail("a"), mail("b")]);
      await press("j");
      expect(scroll).toHaveBeenLastCalledWith({ block: "nearest", behavior: "auto" });
      window.matchMedia = ((q: string) => ({ matches: false, media: q }) as MediaQueryList) as typeof window.matchMedia;
      await press("j");
      expect(scroll).toHaveBeenLastCalledWith({ block: "nearest", behavior: "smooth" });
    } finally {
      delete (Element.prototype as { scrollIntoView?: unknown }).scrollIntoView;
      delete (window as { matchMedia?: unknown }).matchMedia;
    }
  });

  it("an empty list does nothing", async () => {
    await mount([]);
    await press("j");
    await press("Home");
    expect(currentIds()).toEqual([]);
  });
});

describe("Mail tab keyboard: actions on the current mail", () => {
  it("X and Space toggle the checkbox of the current mail", async () => {
    await mount([mail("a"), mail("b")]);
    await press("x"); // no current mail yet
    expect(checkboxes().map((c) => c.checked)).toEqual([false, false]);
    await press("j");
    await press("x");
    expect(checkboxes().map((c) => c.checked)).toEqual([true, false]);
    const space = new KeyboardEvent("keydown", { key: " ", bubbles: true, cancelable: true });
    await act(async () => {
      document.body.dispatchEvent(space);
    });
    expect(space.defaultPrevented).toBe(true);
    expect(checkboxes().map((c) => c.checked)).toEqual([false, false]);
  });

  it("A accepts and D dismisses a pending suggestion", async () => {
    const pending = { suggestedStatus: "Interview", suggestionState: "pending" as const };
    await mount([mail("a", pending), mail("b", pending)]);
    await press("j");
    await press("a");
    expect(accept).toHaveBeenCalledWith("a");
    await press("j");
    await press("d");
    expect(dismiss).toHaveBeenCalledWith("b");
    expect(host.textContent).toContain("Suggestion dismissed");
  });

  it("A and D do nothing, and show no error, for a mail without a pending suggestion", async () => {
    await mount([mail("a"), mail("b", { suggestedStatus: "Rejected", suggestionState: "dismissed" })]);
    await press("j");
    await press("a");
    await press("d");
    await press("j");
    await press("a");
    await press("d");
    expect(accept).not.toHaveBeenCalled();
    expect(dismiss).not.toHaveBeenCalled();
    expect(host.querySelector(".notice.error")).toBeNull();
  });

  it("L opens the picker of applications and moves the focus into it; Escape closes it", async () => {
    await mount([mail("a")]);
    await press("j");
    await press("l");
    await act(async () => {
      await Promise.resolve();
    });
    const select = host.querySelector("select") as HTMLSelectElement;
    expect(select).not.toBeNull();
    expect(listApplications).toHaveBeenCalledTimes(1);
    expect(document.activeElement).toBe(select);
    // Typing in the picker does not fire shortcuts.
    await press("j", {}, select);
    expect(listApplications).toHaveBeenCalledTimes(1);
    await act(async () => {
      select.dispatchEvent(new KeyboardEvent("keydown", { key: "Escape", bubbles: true }));
    });
    expect(host.querySelector("select")).toBeNull();
    expect(document.activeElement).toBe(cards()[0]);
  });

  it("O opens the linked application, and does nothing for an unlinked mail", async () => {
    window.location.hash = "#/mail";
    await mount([mail("a"), mail("b", { vacancyId: "v 1", linkReason: "same sender" })]);
    await press("j");
    await press("o");
    expect(window.location.hash).toBe("#/mail");
    await press("j");
    await press("o");
    expect(window.location.hash).toBe("#/pipeline?open=v%201");
  });

  it("Enter shows more or less of a folded text, and does nothing for a short one", async () => {
    await mount([mail("a", { snippet: LONG }), mail("b")]);
    const more = () => host.querySelector<HTMLButtonElement>(".mail-more");
    expect(more()?.getAttribute("aria-expanded")).toBe("false");
    await press("j");
    await press("Enter");
    expect(more()?.getAttribute("aria-expanded")).toBe("true");
    expect(more()?.textContent).toContain("Show less");
    await press("Enter");
    expect(more()?.textContent).toContain("Show more");
    await press("j");
    await press("Enter");
    expect(more()?.textContent).toContain("Show more");
  });

  it("Enter on a focused button leaves the key to the button", async () => {
    await mount([mail("a", { snippet: LONG })]);
    const button = host.querySelector(".mail-more") as HTMLButtonElement;
    await press("j");
    await press("Enter", {}, button);
    expect(button.getAttribute("aria-expanded")).toBe("false");
  });
});

describe("Mail tab keyboard: when the keys must stay quiet", () => {
  it("does not fire in an input, a textarea or with Ctrl, Meta or Alt held", async () => {
    await mount([mail("a"), mail("b")]);
    const days = host.querySelector("input[aria-label=Days]") as HTMLInputElement;
    await press("j", {}, days);
    const area = document.createElement("textarea");
    document.body.appendChild(area);
    await press("j", {}, area);
    area.remove();
    await press("j", { ctrlKey: true });
    await press("j", { metaKey: true });
    await press("k", { altKey: true });
    expect(currentIds()).toEqual([]);
    await press("?", { ctrlKey: true });
    expect(host.querySelector("#mail-shortcuts")).toBeNull();
  });

  it("does not fire while the confirm dialog is open", async () => {
    await mount([mail("a"), mail("b")]);
    await press("j");
    await press("x");
    const del = [...host.querySelectorAll("button")].find((b) => b.textContent?.startsWith("Delete selected")) as HTMLButtonElement;
    await act(async () => {
      del.click();
    });
    expect(document.querySelector("[role=alertdialog]")).not.toBeNull();
    await press("j");
    await press("x");
    await press("?");
    expect(currentIds()).toEqual(["mail-a"]);
    expect(checkboxes()[0].checked).toBe(true);
    expect(host.querySelector("#mail-shortcuts")).toBeNull();
  });

  it("does not fire while the command palette (an aria-modal dialog) is open", async () => {
    await mount([mail("a"), mail("b")]);
    const palette = document.createElement("div");
    palette.setAttribute("role", "dialog");
    palette.setAttribute("aria-modal", "true");
    document.body.appendChild(palette);
    await press("j");
    palette.remove();
    expect(currentIds()).toEqual([]);
  });
});

describe("Mail tab keyboard: the help", () => {
  const helpButton = () => [...host.querySelectorAll("button")].find((b) => b.textContent?.trim() === "Keyboard shortcuts") as HTMLButtonElement;

  it("is opened and closed by ? and by the button, and closed by Escape", async () => {
    await mount([mail("a")]);
    expect(helpButton().getAttribute("aria-expanded")).toBe("false");
    expect(helpButton().querySelector("svg[data-icon=keyboard]")).not.toBeNull();
    await press("?", { shiftKey: true });
    const help = host.querySelector("#mail-shortcuts") as HTMLElement;
    expect(help).not.toBeNull();
    expect(helpButton().getAttribute("aria-expanded")).toBe("true");
    expect(help.textContent).toContain("Next mail");
    expect(help.textContent).toContain("Accept the suggested status");
    await press("?");
    expect(host.querySelector("#mail-shortcuts")).toBeNull();

    await act(async () => {
      helpButton().click();
    });
    expect(host.querySelector("#mail-shortcuts")).not.toBeNull();
    await press("Escape");
    expect(host.querySelector("#mail-shortcuts")).toBeNull();
    await act(async () => {
      helpButton().click();
    });
    await act(async () => {
      (host.querySelector("#mail-shortcuts button") as HTMLButtonElement).click();
    });
    expect(host.querySelector("#mail-shortcuts")).toBeNull();
  });

  it("works with no mail at all, and Escape with the help closed does nothing", async () => {
    await mount([]);
    await press("Escape");
    expect(host.querySelector("#mail-shortcuts")).toBeNull();
    await press("?");
    expect(host.querySelector("#mail-shortcuts")).not.toBeNull();
  });
});

describe("Mail tab keyboard: the list changes", () => {
  it("keeps the cursor in place when the current mail disappears", async () => {
    await mount([mail("a"), mail("b"), mail("c")]);
    await press("j");
    await press("j");
    expect(currentIds()).toEqual(["mail-b"]);
    listMail.mockResolvedValue([mail("a"), mail("c")]);
    // "Only suggestions" reloads the list.
    const chip = [...host.querySelectorAll("button")].find((b) => b.textContent === "Only suggestions") as HTMLButtonElement;
    await act(async () => {
      chip.click();
    });
    await act(async () => {
      await Promise.resolve();
    });
    expect(currentIds()).toEqual(["mail-c"]);
  });
});
