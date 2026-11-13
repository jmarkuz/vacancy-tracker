import { render } from "preact";
import { act } from "preact/test-utils";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import * as ai from "../api/ai";
import * as vacancies from "../api/vacancies";
import type { Vacancy } from "../api/types";
import { settle } from "../components/loadFailed.helpers";
import { VacancyDrawer } from "./VacancyDrawer";

vi.mock("../api/vacancies", async (importOriginal) => ({
  ...(await importOriginal<typeof vacancies>()),
  getVacancy: vi.fn(),
  getHistory: vi.fn(),
  changeStatus: vi.fn(),
  patchVacancy: vi.fn(),
}));
vi.mock("../api/ai", async (importOriginal) => ({
  ...(await importOriginal<typeof ai>()),
  markSubmitted: vi.fn(),
}));
vi.mock("../components/AnalysisPanel", () => ({ AnalysisPanel: () => <div class="analysis-stub" /> }));
vi.mock("../components/DocumentsPanel", () => ({ DocumentsPanel: () => <div class="documents-stub" /> }));
vi.mock("../components/VacancyMailPanel", () => ({ VacancyMailPanel: () => <div class="mail-stub" /> }));
vi.mock("../components/ApplyPanel", () => ({
  ApplyPanel: (props: { onSubmitted: (packageId: string, note: string) => Promise<void> }) => (
    <button class="apply-stub" type="button" onClick={() => void props.onSubmitted("pkg-1", "sent by mail")}>
      Mark applied
    </button>
  ),
}));

const getVacancy = vi.mocked(vacancies.getVacancy);
const getHistory = vi.mocked(vacancies.getHistory);
const changeStatus = vi.mocked(vacancies.changeStatus);
const patchVacancy = vi.mocked(vacancies.patchVacancy);
const markSubmitted = vi.mocked(ai.markSubmitted);

const vacancy = (extra: Partial<Vacancy> = {}): Vacancy => ({
  id: "v1",
  title: "Frontend dev",
  company: "Acme",
  url: "https://jobs.example/v1",
  canonicalUrl: null,
  description: "Build things",
  notes: "Call back Monday",
  contact: null,
  nextActionNote: null,
  userEditedFields: [],
  location: "Kyiv",
  country: null,
  workMode: "Remote",
  status: "Saved",
  priority: null,
  salaryMin: null,
  salaryMax: null,
  currency: null,
  salaryPeriod: null,
  tags: [],
  nextActionAt: null,
  firstSeenAt: "2026-11-01T10:00:00Z",
  lastSeenAt: "2026-11-02T10:00:00Z",
  isNew: false,
  visaSponsorship: null,
  ...extra,
});

let host: HTMLElement;
let onClose: ReturnType<typeof vi.fn>;
let onChange: ReturnType<typeof vi.fn>;

beforeEach(() => {
  host = document.createElement("div");
  document.body.appendChild(host);
  onClose = vi.fn();
  onChange = vi.fn();
  getHistory.mockResolvedValue([]);
});

afterEach(() => {
  render(null, host);
  host.remove();
  vi.resetAllMocks();
});

const mount = async (id = "v1") => {
  await act(async () => {
    render(<VacancyDrawer id={id} onClose={onClose as never} onChange={onChange as never} />, host);
  });
  await settle(4);
};

const button = (label: string) =>
  Array.from(host.querySelectorAll<HTMLButtonElement>("button")).find((b) => b.textContent?.trim() === label) as HTMLButtonElement;

describe("VacancyDrawer", () => {
  it("shows the vacancy facts once it has loaded", async () => {
    getVacancy.mockResolvedValue(vacancy({ visaSponsorship: true, ghost: { likely: true, signs: 2, reasons: ["Posted long ago"] } }));
    getHistory.mockResolvedValue([{ at: "2026-11-02T10:00:00Z", from: "New", to: "Saved", note: "from mail" } as never]);

    await mount();

    expect(host.querySelector("h2")?.textContent).toBe("Frontend dev");
    expect(host.textContent).toContain("Offered");
    expect(host.textContent).toContain("Posted long ago");
    expect(host.querySelector(".history")?.textContent).toContain("from mail");
    expect(host.querySelector('a[href="https://jobs.example/v1"]')).not.toBeNull();
  });

  it("shows the not-stated and not-offered visa answers and the empty history", async () => {
    getVacancy.mockResolvedValue(vacancy({ visaSponsorship: false, url: "javascript:alert(1)" }));

    await mount();
    expect(host.textContent).toContain("Not offered");
    expect(host.textContent).toContain("No status changes yet.");
    expect(host.querySelector('a[href^="javascript"]')).toBeNull();

    getVacancy.mockResolvedValue(vacancy({ visaSponsorship: null }));
    await mount("v2");
    expect(host.textContent).toContain("Not stated");
  });

  it("says it could not load when the vacancy fails to load", async () => {
    getVacancy.mockRejectedValue(new Error("gone"));
    getHistory.mockResolvedValue([]);

    await mount();

    expect(host.querySelector('[role="alert"]')?.textContent).toContain("Could not load this vacancy.");
    expect(host.querySelector("h2")?.textContent).toBe("Vacancy");
  });

  it("closes on Escape and on a click on the scrim", async () => {
    getVacancy.mockResolvedValue(vacancy());
    await mount();

    await act(async () => {
      window.dispatchEvent(new KeyboardEvent("keydown", { key: "Escape" }));
    });
    expect(onClose).toHaveBeenCalledTimes(1);

    await act(async () => {
      (host.querySelector(".scrim") as HTMLElement).click();
    });
    expect(onClose).toHaveBeenCalledTimes(2);
  });

  it("moves the status to the chosen step, saves it and reloads the history", async () => {
    getVacancy.mockResolvedValue(vacancy());
    changeStatus.mockResolvedValue(vacancy({ status: "Applied" }));
    await mount();

    const select = host.querySelector<HTMLSelectElement>(".drawer-row select")!;
    const options = Array.from(select.options).map((o) => o.value).filter(Boolean);
    expect(options.length).toBeGreaterThan(0);
    expect(button("Move").disabled).toBe(true);

    await act(async () => {
      select.value = options[0];
      select.dispatchEvent(new Event("change", { bubbles: true }));
    });
    await act(async () => {
      button("Move").click();
    });
    await settle(4);

    expect(changeStatus).toHaveBeenCalledWith("v1", options[0]);
    expect(onChange).toHaveBeenCalled();
    expect(getHistory).toHaveBeenCalledTimes(2);
  });

  it("shows a save error when a status change fails and keeps the vacancy", async () => {
    getVacancy.mockResolvedValue(vacancy());
    changeStatus.mockRejectedValue(new Error("Conflict"));
    await mount();

    await act(async () => {
      const select = host.querySelector<HTMLSelectElement>(".drawer-row select")!;
      select.value = select.options[1].value;
      select.dispatchEvent(new Event("change", { bubbles: true }));
    });
    await act(async () => {
      button("Move").click();
    });
    await settle(4);

    expect(host.querySelector('.drawer-body [role="alert"]')?.textContent).toBe("Save failed. Your change was not stored.");
    expect(onChange).not.toHaveBeenCalled();
  });

  it("saves an edited description and notes through their own endpoints", async () => {
    getVacancy.mockResolvedValue(vacancy({ userEditedFields: ["description"] }));
    patchVacancy.mockResolvedValue(vacancy());
    await mount();

    expect(host.querySelector(".edited-mark")).not.toBeNull();

    const description = host.querySelector<HTMLTextAreaElement>('textarea[aria-label="Description"]')!;
    await act(async () => {
      description.value = "Build better things";
      description.dispatchEvent(new Event("input", { bubbles: true }));
    });
    await act(async () => {
      button("Save description").click();
    });
    await settle(4);
    expect(patchVacancy).toHaveBeenCalledWith("v1", { description: "Build better things" });

    const notes = host.querySelector<HTMLTextAreaElement>('textarea[aria-label="Notes"]')!;
    await act(async () => {
      notes.value = "Follow up Friday";
      notes.dispatchEvent(new Event("input", { bubbles: true }));
    });
    await act(async () => {
      button("Save notes").click();
    });
    await settle(4);
    expect(patchVacancy).toHaveBeenCalledWith("v1", { notes: "Follow up Friday" });
  });

  it("sets the priority and keeps the save disabled until it differs", async () => {
    getVacancy.mockResolvedValue(vacancy({ priority: 2 }));
    patchVacancy.mockResolvedValue(vacancy({ priority: 1 }));
    await mount();

    expect(button("Set priority").disabled).toBe(true);

    const select = host.querySelector<HTMLSelectElement>('select[aria-label="Priority"]')!;
    await act(async () => {
      select.value = "1";
      select.dispatchEvent(new Event("change", { bubbles: true }));
    });
    await act(async () => {
      button("Set priority").click();
    });
    await settle(4);

    expect(patchVacancy).toHaveBeenCalledWith("v1", { priority: 1 });
  });

  it("marks the vacancy applied through the apply panel and saves the change", async () => {
    getVacancy.mockResolvedValue(vacancy());
    markSubmitted.mockResolvedValue(undefined as never);
    await mount();

    await act(async () => {
      (host.querySelector(".apply-stub") as HTMLButtonElement).click();
    });
    await settle(4);

    expect(markSubmitted).toHaveBeenCalledWith("pkg-1", "sent by mail");
    expect(onChange).toHaveBeenCalled();
  });

  it("closes with Escape only when focus is not in a field", async () => {
    getVacancy.mockResolvedValue(vacancy());
    await mount();

    const notes = host.querySelector<HTMLTextAreaElement>('textarea[aria-label="Notes"]')!;
    notes.focus();
    await act(async () => {
      notes.dispatchEvent(new KeyboardEvent("keydown", { key: "Escape", bubbles: true }));
    });

    expect(onClose).not.toHaveBeenCalled();
  });

  it("keeps Tab inside the dialog", async () => {
    getVacancy.mockResolvedValue(vacancy());
    await mount();

    const panel = host.querySelector<HTMLElement>(".drawer")!;
    const focusable = Array.from(panel.querySelectorAll<HTMLElement>("button:not([disabled]), a[href], textarea, select"));
    const first = focusable[0];
    const last = focusable[focusable.length - 1];

    last.focus();
    await act(async () => {
      panel.dispatchEvent(new KeyboardEvent("keydown", { key: "Tab", bubbles: true }));
    });
    expect(document.activeElement).toBe(first);

    first.focus();
    await act(async () => {
      panel.dispatchEvent(new KeyboardEvent("keydown", { key: "Tab", shiftKey: true, bubbles: true }));
    });
    expect(document.activeElement).toBe(last);
  });
});
