import { render } from "preact";
import { act } from "preact/test-utils";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import * as client from "../api/client";
import * as resume from "../api/resume";
import * as vacancies from "../api/vacancies";
import type { Vacancy } from "../api/types";
import { settle } from "../components/loadFailed.helpers";
import { Inbox } from "./Inbox";

vi.mock("../api/client", async (importOriginal) => ({
  ...(await importOriginal<typeof client>()),
  getJson: vi.fn(),
}));
vi.mock("../api/resume", async (importOriginal) => ({
  ...(await importOriginal<typeof resume>()),
  getFits: vi.fn(),
}));
vi.mock("../api/vacancies", async (importOriginal) => ({
  ...(await importOriginal<typeof vacancies>()),
  changeStatus: vi.fn(),
}));
vi.mock("../router", () => ({ setQuery: vi.fn() }));
vi.mock("../components/InboxInspector", () => ({
  InboxInspector: (props: { vacancy: Vacancy; onSave: () => void; onSkip: () => void }) => (
    <div class="inspector-stub">
      <span>{props.vacancy.title}</span>
      <button type="button" onClick={props.onSave}>Save it</button>
      <button type="button" onClick={props.onSkip}>Skip it</button>
    </div>
  ),
}));
vi.mock("./VacancyDrawer", () => ({ VacancyDrawer: () => <div class="drawer-stub" /> }));

const getJson = vi.mocked(client.getJson);
const getFits = vi.mocked(resume.getFits);
const changeStatus = vi.mocked(vacancies.changeStatus);

const vacancy = (id: string, title: string, extra: Partial<Vacancy> = {}): Vacancy => ({
  id,
  title,
  company: "Acme",
  url: `https://jobs.example/${id}`,
  canonicalUrl: null,
  description: null,
  notes: null,
  contact: null,
  nextActionNote: null,
  userEditedFields: [],
  location: "Kyiv",
  country: null,
  workMode: "Remote",
  status: "New",
  priority: null,
  salaryMin: null,
  salaryMax: null,
  currency: null,
  salaryPeriod: null,
  tags: [],
  nextActionAt: null,
  firstSeenAt: "2026-11-01T10:00:00Z",
  lastSeenAt: "2026-11-02T10:00:00Z",
  isNew: true,
  ...extra,
});

let host: HTMLElement;

beforeEach(() => {
  host = document.createElement("div");
  document.body.appendChild(host);
  getFits.mockResolvedValue([]);
});

afterEach(() => {
  render(null, host);
  host.remove();
  vi.resetAllMocks();
});

const page = (items: Vacancy[]) => ({ items, total: items.length, page: 0, size: 100 });

const mount = async (params = new URLSearchParams(), dataVersion = 0) => {
  await act(async () => {
    render(<Inbox params={params} dataVersion={dataVersion} />, host);
  });
  await settle(4);
};

const key = (k: string) => {
  window.dispatchEvent(new KeyboardEvent("keydown", { key: k }));
};

const selectedTitle = () => host.querySelector('[aria-selected="true"] .inbox-title')?.textContent;

describe("Inbox", () => {
  it("lists the New vacancies and selects the first one", async () => {
    getJson.mockResolvedValue(page([vacancy("a", "Frontend dev"), vacancy("b", "Backend dev")]));

    await mount();

    expect(getJson.mock.calls[0][0]).toContain("/api/vacancies?status=New");
    expect(host.querySelectorAll(".inbox-row")).toHaveLength(2);
    expect(selectedTitle()).toBe("Frontend dev");
    expect(host.querySelector(".inspector-stub")?.textContent).toContain("Frontend dev");
  });

  it("shows the empty Inbox when nothing is new and no filter is set", async () => {
    getJson.mockResolvedValue(page([]));

    await mount();

    expect(host.querySelector("h2")?.textContent).toBe("Inbox is empty");
    expect(host.querySelector(".inspector-stub")).toBeNull();
  });

  it("offers to clear the filter when the filter matches nothing", async () => {
    getJson.mockResolvedValue(page([]));

    await mount(new URLSearchParams("q=rust"));

    expect(host.querySelector("h2")?.textContent).toBe("No new vacancy matches the filter");
    expect(getJson.mock.calls[0][0]).toContain("filter=");
  });

  it("says why the Inbox could not load and retries on Retry", async () => {
    getJson.mockRejectedValueOnce(new Error("offline"));

    await mount();
    expect(host.querySelector('[role="alert"]')?.textContent).toContain("Could not load the Inbox.");

    getJson.mockResolvedValue(page([vacancy("a", "Frontend dev")]));
    await act(async () => {
      (host.querySelector('[role="alert"] button') as HTMLButtonElement).click();
    });
    await settle(4);

    expect(host.querySelectorAll(".inbox-row")).toHaveLength(1);
  });

  it("moves the selection with J and K and stays within the list", async () => {
    getJson.mockResolvedValue(page([vacancy("a", "First"), vacancy("b", "Second")]));
    await mount();

    key("j");
    await settle(1);
    expect(selectedTitle()).toBe("Second");

    key("j");
    await settle(1);
    expect(selectedTitle()).toBe("Second");

    key("k");
    await settle(1);
    expect(selectedTitle()).toBe("First");
  });

  it("saves the highlighted vacancy with S and removes it from the list", async () => {
    getJson.mockResolvedValue(page([vacancy("a", "First"), vacancy("b", "Second")]));
    changeStatus.mockResolvedValue(vacancy("a", "First", { status: "Saved" }));
    await mount();

    await act(async () => {
      key("s");
    });
    await settle(4);

    expect(changeStatus).toHaveBeenCalledWith("a", "Saved");
    expect(host.querySelectorAll(".inbox-row")).toHaveLength(1);
    expect(selectedTitle()).toBe("Second");
    expect(host.textContent).toContain("Saved: First");
  });

  it("skips the highlighted vacancy with X from the inspector button", async () => {
    getJson.mockResolvedValue(page([vacancy("a", "First"), vacancy("b", "Second")]));
    changeStatus.mockResolvedValue(vacancy("a", "First", { status: "Dismissed" }));
    await mount();

    await act(async () => {
      (host.querySelectorAll(".inspector-stub button")[1] as HTMLButtonElement).click();
    });
    await settle(4);

    expect(changeStatus).toHaveBeenCalledWith("a", "Dismissed");
    expect(host.querySelectorAll(".inbox-row")).toHaveLength(1);
  });

  it("keeps the vacancy and shows an error when the change fails", async () => {
    getJson.mockResolvedValue(page([vacancy("a", "First")]));
    changeStatus.mockRejectedValue(new Error("Server said no"));
    await mount();

    await act(async () => {
      key("x");
    });
    await settle(4);

    expect(host.querySelectorAll(".inbox-row")).toHaveLength(1);
    expect(host.textContent).toContain("Server said no");
  });

  it("does not act on keys while the details drawer is open", async () => {
    getJson.mockResolvedValue(page([vacancy("a", "First")]));
    await mount(new URLSearchParams("open=a"));

    await act(async () => {
      key("s");
    });
    await settle(2);

    expect(changeStatus).not.toHaveBeenCalled();
  });

  it("reloads the list when the data version changes", async () => {
    getJson.mockResolvedValue(page([vacancy("a", "First")]));
    await mount();
    expect(getJson).toHaveBeenCalledTimes(1);

    await act(async () => {
      render(<Inbox params={new URLSearchParams()} dataVersion={1} />, host);
    });
    await settle(4);

    expect(getJson).toHaveBeenCalledTimes(2);
  });
});
