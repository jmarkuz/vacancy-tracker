import { render } from "preact";
import { act } from "preact/test-utils";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "../api/client";
import * as mailApi from "../api/mail";
import type { ApplicationOption, Mail } from "../api/mail";
import { MailList } from "./MailList";

vi.mock("../api/mail", async (importOriginal) => ({
  ...(await importOriginal<typeof mailApi>()),
  listApplications: vi.fn(),
  relinkMail: vi.fn(),
  addDigestJob: vi.fn(),
}));

const listApplications = vi.mocked(mailApi.listApplications);
const relinkMail = vi.mocked(mailApi.relinkMail);
const addDigestJob = vi.mocked(mailApi.addDigestJob);

const mail = (patch: Partial<Mail> = {}): Mail => ({
  id: "m1",
  mailKey: "k1",
  folder: "INBOX",
  sender: "jane@recruiting.example",
  senderName: "Jane",
  subject: "Quick question",
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

const app = (id: string, title: string, status = "Applied"): ApplicationOption => ({ id, title, company: "Zeta Corp", status });

let host: HTMLElement;
const onChanged = vi.fn();

const button = (label: string) => [...host.querySelectorAll("button")].find((b) => b.textContent?.trim() === label) as HTMLButtonElement;
const select = () => host.querySelector("select") as HTMLSelectElement;

async function flush() {
  await act(async () => {
    await Promise.resolve();
  });
}

async function show(items: Mail[]) {
  await act(async () => {
    render(<MailList items={items} onChanged={onChanged} />, host);
  });
}

beforeEach(() => {
  host = document.createElement("div");
  document.body.appendChild(host);
  relinkMail.mockResolvedValue(mail());
});

afterEach(() => {
  render(null, host);
  host.remove();
  vi.resetAllMocks();
  onChanged.mockReset();
});

describe("MailList: link a mail to an application", () => {
  it("offers the applications and links the mail to the chosen one", async () => {
    listApplications.mockResolvedValue([app("v1", "Backend Engineer"), app("v2", "Platform Engineer")]);
    await show([mail()]);

    await act(async () => button("Link to an application").click());
    await flush();
    expect([...select().options].map((o) => o.textContent)).toEqual([
      "Choose an application…",
      "Backend Engineer · Zeta Corp (Applied)",
      "Platform Engineer · Zeta Corp (Applied)",
    ]);
    await act(async () => {
      select().value = "v2";
      select().dispatchEvent(new Event("change", { bubbles: true }));
    });
    await flush();

    expect(relinkMail).toHaveBeenCalledWith("m1", "v2");
    expect(onChanged).toHaveBeenCalledWith("Mail linked to the application.");
  });

  it("reads the applications again each time linking opens, so a changed list is never stale", async () => {
    listApplications.mockResolvedValueOnce([app("v1", "Backend Engineer")]);
    await show([mail()]);
    await act(async () => button("Link to an application").click());
    await flush();
    expect(select().options).toHaveLength(2);
    await act(async () => button("Cancel").click());

    listApplications.mockResolvedValueOnce([app("v1", "Backend Engineer"), app("v3", "New Role", "Screening")]);
    await act(async () => button("Link to an application").click());
    await flush();

    expect(listApplications).toHaveBeenCalledTimes(2);
    expect([...select().options].map((o) => o.textContent)).toContain("New Role · Zeta Corp (Screening)");
  });

  it("does not offer the application the mail is already linked to, and can unlink", async () => {
    listApplications.mockResolvedValue([app("v1", "Backend Engineer"), app("v2", "Platform Engineer")]);
    await show([mail({ vacancyId: "v1", linkReason: "Linked by you." })]);

    await act(async () => button("Change application").click());
    await flush();
    expect([...select().options].map((o) => o.textContent)).not.toContain("Backend Engineer · Zeta Corp (Applied)");
    await act(async () => button("Cancel").click());
    await act(async () => button("Unlink").click());
    await flush();

    expect(relinkMail).toHaveBeenCalledWith("m1", null);
    expect(onChanged).toHaveBeenCalledWith("Mail unlinked.");
  });

  it("says so when there are no applications yet", async () => {
    listApplications.mockResolvedValue([]);
    await show([mail()]);

    await act(async () => button("Link to an application").click());
    await flush();

    expect(select().options[0].textContent).toBe("No applications yet");
  });

  it("shows an error and closes the chooser when the applications cannot be loaded", async () => {
    listApplications.mockRejectedValue(new Error("offline"));
    await show([mail()]);

    await act(async () => button("Link to an application").click());
    await flush();

    expect(host.querySelector('[role="alert"]')?.textContent).toContain("Could not load your applications.");
    expect(host.querySelector("select")).toBeNull();
  });

  it("shows the server's message when linking is refused", async () => {
    listApplications.mockResolvedValue([app("v1", "Backend Engineer")]);
    relinkMail.mockRejectedValue(new ApiError(404, "Vacancy not found"));
    await show([mail()]);
    await act(async () => button("Link to an application").click());
    await flush();

    await act(async () => {
      select().value = "v1";
      select().dispatchEvent(new Event("change", { bubbles: true }));
    });
    await flush();

    expect(host.querySelector('[role="alert"]')?.textContent).toContain("Vacancy not found");
    expect(onChanged).not.toHaveBeenCalled();
  });

  it("ignores a slow answer from an earlier opening", async () => {
    let resolveFirst: (v: ApplicationOption[]) => void = () => undefined;
    listApplications.mockImplementationOnce(() => new Promise((r) => { resolveFirst = r; }));
    await show([mail()]);
    await act(async () => button("Link to an application").click()); // first opening, still loading
    await act(async () => button("Cancel").click());
    listApplications.mockResolvedValueOnce([app("v9", "Fresh Role")]);
    await act(async () => button("Link to an application").click());
    await flush();

    await act(async () => resolveFirst([app("v1", "Stale Role")]));
    await flush();

    const labels = [...select().options].map((o) => o.textContent ?? "");
    expect(labels.some((l) => l.includes("Fresh Role"))).toBe(true);
    expect(labels.some((l) => l.includes("Stale Role"))).toBe(false);
  });
});

const posting = (n: number, patch: Partial<mailApi.DigestJob> = {}): mailApi.DigestJob => ({
  title: `Java Developer ${n}`,
  company: `Company ${n}`,
  location: "Berlin",
  url: `https://www.linkedin.com/jobs/view/${n}/`,
  ...patch,
});

describe("MailList: a digest mail lists its vacancies", () => {
  it("shows each vacancy with its company and place and a link to its page, instead of the text", async () => {
    await show([mail({ category: "other_job", snippet: "raw text of the digest", jobs: [posting(1), posting(2), posting(3)] })]);

    expect(host.querySelector(".mail-category")?.textContent).toBe("Job alert");
    expect(host.querySelector(".digest-heading")?.textContent).toBe("3 vacancies in this mail");
    const rows = [...host.querySelectorAll(".digest-job")];
    expect(rows.length).toBe(3);
    expect(rows[1].querySelector(".digest-title")?.textContent).toBe("Java Developer 2");
    expect(rows[1].querySelector(".digest-meta")?.textContent).toBe("Company 2 · Berlin");
    const link = rows[1].querySelector("a") as HTMLAnchorElement;
    expect(link.getAttribute("href")).toBe("https://www.linkedin.com/jobs/view/2/");
    expect(link.getAttribute("target")).toBe("_blank");
    expect(link.getAttribute("rel")).toContain("noopener");
    expect(link.getAttribute("aria-label")).toContain("Java Developer 2");
    expect(host.textContent).not.toContain("raw text of the digest");
  });

  it("leaves out the link of a posting whose address is not a web address, and the company it does not have", async () => {
    await show([mail({ jobs: [posting(1, { url: "javascript:alert(1)", location: null }), posting(2, { url: null, company: null })] })]);

    expect(host.querySelectorAll(".digest-link").length).toBe(0);
    expect(host.querySelectorAll(".digest-job")[0].querySelector(".digest-meta")?.textContent).toBe("Company 1");
    expect(host.querySelectorAll(".digest-job")[1].querySelector(".digest-meta")?.textContent).toBe("Berlin");
  });

  it("treats one posting as an ordinary mail", async () => {
    await show([mail({ snippet: "Hello there", jobs: [posting(1)] })]);

    expect(host.querySelector(".digest")).toBeNull();
    expect(host.querySelector(".mail-category")?.textContent).toBe("Recruiter message");
    expect(host.querySelector(".mail-text")?.textContent).toBe("Hello there");
  });
});

describe("MailList: a digest vacancy goes to the Pipeline", () => {
  const addButton = (row: number) => host.querySelectorAll(".digest-job")[row].querySelector("button.digest-add") as HTMLButtonElement | null;

  it("adds the posting, then shows In Pipeline with an Open link to the new vacancy", async () => {
    addDigestJob.mockResolvedValue({ vacancyId: "v-new", created: true });
    await show([mail({ id: "m7", jobs: [posting(1), posting(2)] })]);
    expect(addButton(1)?.textContent).toBe("Add to Pipeline");
    expect(addButton(1)?.getAttribute("aria-label")).toBe("Add Java Developer 2 to Pipeline");

    await act(async () => addButton(1)?.click());

    expect(addDigestJob).toHaveBeenCalledWith("m7", 1);
    const row = host.querySelectorAll(".digest-job")[1];
    expect(row.querySelector(".digest-state")?.textContent).toBe("In Pipeline");
    expect(row.querySelector("button")).toBeNull();
    const open = [...row.querySelectorAll("a")].find((a) => a.textContent === "Open") as HTMLAnchorElement;
    expect(open.getAttribute("href")).toBe("#/pipeline?open=v-new");
    expect(addButton(0)?.textContent).toBe("Add to Pipeline");
  });

  it("disables the button while it works and sends one request for a double click", async () => {
    let finish: (v: { vacancyId: string; created: boolean }) => void = () => undefined;
    addDigestJob.mockReturnValue(new Promise((resolve) => (finish = resolve)));
    await show([mail({ jobs: [posting(1), posting(2)] })]);

    await act(async () => {
      addButton(0)?.click();
      addButton(0)?.click();
    });
    expect(addDigestJob).toHaveBeenCalledTimes(1);
    expect(addButton(0)?.disabled).toBe(true);
    expect(addButton(0)?.textContent).toBe("Adding…");

    await act(async () => finish({ vacancyId: "v1", created: true }));
    expect(addButton(0)).toBeNull();
  });

  it("shows the error and lets the owner try again", async () => {
    addDigestJob.mockRejectedValueOnce(new ApiError(500, "Boom")).mockResolvedValueOnce({ vacancyId: "v2", created: false });
    await show([mail({ jobs: [posting(1), posting(2)] })]);

    await act(async () => addButton(0)?.click());
    expect(host.querySelector(".digest .notice.error")?.textContent).toContain("Boom");
    expect(addButton(0)?.disabled).toBe(false);

    await act(async () => addButton(0)?.click());
    expect(host.querySelector(".digest .notice.error")).toBeNull();
    expect(host.querySelectorAll(".digest-state").length).toBe(1);
  });

  it("uses a generic message when the failure is not an API error", async () => {
    addDigestJob.mockRejectedValue(new Error("network"));
    await show([mail({ jobs: [posting(1), posting(2)] })]);

    await act(async () => addButton(0)?.click());

    expect(host.querySelector(".digest .notice.error")?.textContent).toBe("Could not add the vacancy to the Pipeline.");
  });

  it("shows In Pipeline at once for a posting the server found among the vacancies", async () => {
    await show([mail({ jobs: [posting(1, { vacancyId: "v9" }), posting(2, { vacancyId: null })] })]);

    const first = host.querySelectorAll(".digest-job")[0];
    expect(first.querySelector(".digest-state")?.textContent).toBe("In Pipeline");
    expect(first.querySelector("button")).toBeNull();
    expect([...first.querySelectorAll("a")].some((a) => a.getAttribute("href") === "#/pipeline?open=v9")).toBe(true);
    expect(addButton(1)).not.toBeNull();
  });

  it("offers no button for a posting without a web link or a company, since it could not be matched or saved", async () => {
    await show([mail({ jobs: [posting(1, { url: null }), posting(2, { company: null }), posting(3, { url: "javascript:alert(1)" })] })]);

    expect(host.querySelectorAll("button.digest-add").length).toBe(0);
    expect(host.querySelectorAll(".digest-state").length).toBe(0);
  });
});

describe("MailList: the text of an ordinary mail", () => {
  it("is shown as paragraphs", async () => {
    await show([mail({ snippet: "Hi Jane,\nWe would like to talk.\nBest," })]);

    expect([...host.querySelectorAll(".mail-text p")].map((p) => p.textContent)).toEqual(["Hi Jane,", "We would like to talk.", "Best,"]);
    expect(host.querySelector(".mail-more")).toBeNull();
  });

  it("is folded when long, and unfolds and folds again with the button", async () => {
    await show([mail({ snippet: "line\n".repeat(8) })]);

    expect(host.querySelector(".mail-text")?.classList.contains("folded")).toBe(true);
    const more = host.querySelector(".mail-more") as HTMLButtonElement;
    expect(more.textContent?.trim()).toBe("Show more");
    expect(more.getAttribute("aria-expanded")).toBe("false");

    await act(async () => {
      more.click();
    });
    expect(host.querySelector(".mail-text")?.classList.contains("folded")).toBe(false);
    expect((host.querySelector(".mail-more") as HTMLButtonElement).textContent?.trim()).toBe("Show less");

    await act(async () => {
      (host.querySelector(".mail-more") as HTMLButtonElement).click();
    });
    expect(host.querySelector(".mail-text")?.classList.contains("folded")).toBe(true);
  });

  it("is left out when the mail has no text", async () => {
    await show([mail({ snippet: null })]);

    expect(host.querySelector(".mail-body")).toBeNull();
  });

  it("tells under an unfolded cut text that the rest is in the mailbox, and not for a whole text", async () => {
    await show([mail({ snippet: "line\n".repeat(8) + "last words…" }), mail({ id: "m2", mailKey: "k2", snippet: "line\n".repeat(8) })]);
    expect(host.querySelector(".mail-rest")).toBeNull();

    const more = host.querySelectorAll<HTMLButtonElement>(".mail-more");
    await act(async () => {
      more[0].click();
      more[1].click();
    });

    const notes = host.querySelectorAll(".mail-rest");
    expect(notes).toHaveLength(1);
    expect(notes[0].textContent).toBe("The rest is in your mailbox.");

    await act(async () => {
      host.querySelectorAll<HTMLButtonElement>(".mail-more")[0].click();
    });
    expect(host.querySelector(".mail-rest")).toBeNull();
  });
});
