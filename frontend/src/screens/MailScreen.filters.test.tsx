import { render } from "preact";
import { act } from "preact/test-utils";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import * as mailApi from "../api/mail";
import type { Mail, MailCounts, MailStatus } from "../api/mail";
import { SEARCH_DEBOUNCE_MS } from "../components/MailFilterBar";
import { MailScreen } from "./MailScreen";

vi.mock("../api/mail", async (importOriginal) => ({
  ...(await importOriginal<typeof mailApi>()),
  getMailStatus: vi.fn(),
  listMail: vi.fn(),
  getMailCounts: vi.fn(),
  deleteMail: vi.fn(),
}));

const getStatus = vi.mocked(mailApi.getMailStatus);
const listMail = vi.mocked(mailApi.listMail);
const getCounts = vi.mocked(mailApi.getMailCounts);

const mail = (id: string): Mail => ({
  id,
  mailKey: `k-${id}`,
  folder: "INBOX",
  sender: "jane@recruiting.example",
  senderName: "Jane",
  subject: `Subject ${id}`,
  receivedAt: "2026-11-19T10:00:00Z",
  snippet: "Hello",
  category: "recruiter_message",
  vacancyId: null,
  linkReason: null,
  suggestedStatus: null,
  suggestionState: "none",
  createdAt: "2026-11-19T10:00:00Z",
  jobs: [],
});
const status: MailStatus = {
  configured: false,
  folders: [],
  pending: 0,
  lastSync: null,
  lastError: null,
  syncing: false,
  lastResult: null,
  intervalMinutes: 15,
  nextCheck: null,
};
const counts: MailCounts = {
  total: 5,
  categories: { job_alert: 2, interview: 1, offer: 0, rejection: 1, test_task: 0, recruiter_message: 1, other: 0 },
};

let host: HTMLElement;

const settle = async (ms = 0) => {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(ms);
  });
};
const chip = (label: string) =>
  [...host.querySelectorAll("button.chip")].find((b) => b.firstChild?.textContent?.trim() === label) as HTMLButtonElement;
const click = async (el: HTMLElement) => {
  await act(async () => {
    el.click();
  });
  await settle();
};
const search = () => host.querySelector("#mail-search") as HTMLInputElement;
const type = async (value: string) => {
  await act(async () => {
    search().value = value;
    search().dispatchEvent(new Event("input", { bubbles: true }));
  });
};
const lastQuery = () => listMail.mock.calls[listMail.mock.calls.length - 1][0];
const subjects = () => [...host.querySelectorAll(".mail-list > li")].length;

async function mount(hash = "#/mail") {
  window.location.hash = hash;
  await act(async () => {
    render(<MailScreen />, host);
  });
  await settle();
}

beforeEach(() => {
  vi.useFakeTimers();
  host = document.createElement("div");
  document.body.appendChild(host);
  getStatus.mockResolvedValue(status);
  listMail.mockResolvedValue([mail("a"), mail("b")]);
  getCounts.mockResolvedValue(counts);
});

afterEach(() => {
  render(null, host);
  host.remove();
  vi.useRealTimers();
  vi.resetAllMocks();
  window.location.hash = "";
});

describe("Mail filters: chips, counts and the URL", () => {
  it("lists everything at first and shows the server's counts on the chips", async () => {
    await mount();

    expect(lastQuery()).toMatchObject({ categories: [], linked: undefined, q: "" });
    expect(chip("Job alerts").querySelector(".chip-count")?.textContent).toBe("2");
    expect(chip("Offers").querySelector(".chip-count")?.textContent).toBe("0");
    expect(chip("Offers").getAttribute("aria-pressed")).toBe("false");
    expect(host.textContent).not.toContain("Clear filters");
  });

  it("picks several kinds, puts them in the URL and asks the server again", async () => {
    await mount();

    await click(chip("Rejections"));
    await click(chip("Interviews"));

    expect(window.location.hash).toBe("#/mail?cat=interview%2Crejection");
    expect(lastQuery()).toMatchObject({ categories: ["interview", "rejection"] });
    expect(chip("Interviews").getAttribute("aria-pressed")).toBe("true");

    await click(chip("Interviews"));
    expect(window.location.hash).toBe("#/mail?cat=rejection");
  });

  it("filters by linked state", async () => {
    await mount();

    await click(chip("Not linked"));
    expect(window.location.hash).toBe("#/mail?linked=false");
    expect(lastQuery()).toMatchObject({ linked: false });

    await click(chip("Linked"));
    expect(lastQuery()).toMatchObject({ linked: true });

    await click(chip("All"));
    expect(window.location.hash).toBe("#/mail");
    expect(lastQuery()).toMatchObject({ linked: undefined });
  });

  it("restores the filters from the URL after a reload", async () => {
    await mount("#/mail?cat=offer&linked=true&q=acme");

    expect(lastQuery()).toMatchObject({ categories: ["offer"], linked: true, q: "acme" });
    expect(chip("Offers").getAttribute("aria-pressed")).toBe("true");
    expect(chip("Linked").getAttribute("aria-pressed")).toBe("true");
    expect(search().value).toBe("acme");
    expect(getCounts.mock.calls[0][0]).toMatchObject({ q: "acme", linked: true });
  });

  it("still lists the mail when the counts cannot be loaded, without numbers on the chips", async () => {
    getCounts.mockRejectedValue(new Error("down"));

    await mount();

    expect(subjects()).toBe(2);
    expect(host.querySelector(".chip-count")).toBeNull();
  });
});

describe("Mail search", () => {
  it("waits for a pause in typing before it searches", async () => {
    await mount();
    const calls = listMail.mock.calls.length;

    await type("ac");
    await settle(SEARCH_DEBOUNCE_MS - 50);
    await type("acme");
    await settle(SEARCH_DEBOUNCE_MS - 50);
    expect(listMail.mock.calls.length).toBe(calls);
    expect(window.location.hash).toBe("#/mail");

    await settle(60);
    expect(window.location.hash).toBe("#/mail?q=acme");
    expect(lastQuery()).toMatchObject({ q: "acme" });
    expect(listMail.mock.calls.length).toBe(calls + 1);
    expect(search().value).toBe("acme");
  });

  it("has a label, type search and a clear button that empties it at once", async () => {
    await mount("#/mail?q=acme");

    expect(search().type).toBe("search");
    expect(host.querySelector('label[for="mail-search"]')?.textContent).toContain("Search mail");
    await click(host.querySelector('button[aria-label="Clear search"]') as HTMLElement);

    expect(search().value).toBe("");
    expect(window.location.hash).toBe("#/mail");
    expect(host.querySelector('button[aria-label="Clear search"]')).toBeNull();
  });

  it("does not search for text typed just before Clear filters", async () => {
    await mount("#/mail?cat=offer");
    await type("late");
    await click(host.querySelector("button.mail-filters-clear") as HTMLElement);
    await settle(SEARCH_DEBOUNCE_MS * 2);

    expect(window.location.hash).toBe("#/mail");
    expect(search().value).toBe("");
  });

  it("follows the URL when it changes from outside (Back)", async () => {
    await mount("#/mail?q=one");
    await act(async () => {
      window.location.hash = "#/mail?q=two";
      window.dispatchEvent(new HashChangeEvent("hashchange"));
    });
    await settle();

    expect(search().value).toBe("two");
    expect(lastQuery()).toMatchObject({ q: "two" });
  });
});

describe("Mail: nothing matches", () => {
  it("says so and Clear filters brings everything back", async () => {
    listMail.mockResolvedValueOnce([]);
    await mount("#/mail?cat=offer&q=zzz");

    expect(host.textContent).toContain("No mail matches these filters");
    expect(host.querySelector(".mail-tools")).toBeNull();

    listMail.mockResolvedValue([mail("a")]);
    const clear = [...host.querySelectorAll("button")].filter((b) => b.textContent === "Clear filters");
    expect(clear.length).toBe(2);
    await click(clear[clear.length - 1]);

    expect(window.location.hash).toBe("#/mail");
    expect(host.textContent).not.toContain("No mail matches");
    expect(subjects()).toBe(1);
    expect(search().value).toBe("");
  });

  it("keeps the plain empty text when no filter is on", async () => {
    listMail.mockResolvedValue([]);
    await mount();

    expect(host.textContent).toContain("No job mail yet.");
    expect(host.textContent).not.toContain("No mail matches");
  });
});

describe("Mail: selection follows the filtered list", () => {
  it("Select all takes the mails on the screen, and a new filter drops the choices that left", async () => {
    await mount();
    const selectAll = () => [...host.querySelectorAll("button")].find((b) => /Select all|Clear selection/.test(b.textContent ?? "")) as HTMLButtonElement;
    await click(selectAll());
    expect(host.textContent).toContain("Delete selected (2)");

    listMail.mockResolvedValue([mail("b")]);
    await click(chip("Offers"));

    expect(subjects()).toBe(1);
    expect(host.textContent).toContain("Delete selected (1)");
  });
});
