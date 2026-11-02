import { expect, test, type APIRequestContext, type Page } from "@playwright/test";

/**
 * M7 through the UI (T-099), with fake data and the fake AI provider only: the default preset is filled in, the
 * vacancy shows Employer match and Goal match, both documents are generated from the base drafts and score higher
 * than the base ("Base → Tailored"), the fact check warns about the added skill, the resume PDF has one or two pages,
 * and the package leads to Applied. Then: a resume too long for two pages is refused, and Use as is works with AI
 * off. Runs after the full-cycle test on the same database.
 */
test.describe.configure({ mode: "serial" });

const TITLE = "Senior Java Engineer";
const COMPANY = "Beta Bank";
/** Java and PostgreSQL proved in experience, Kafka only in Skills, Kubernetes nowhere. */
const BASE_RESUME = [
  "# Jane Doe",
  "Kyiv | jane@example.com",
  "",
  "## Experience",
  "",
  "### Backend Engineer, Acme, Remote",
  "Jan 2020 to Present",
  "- Cut payment latency by 40% with Java and PostgreSQL.",
  "",
  "## Skills",
  "Java, PostgreSQL, Kafka",
].join("\n");
let vacancyId = "";
let applyUrl = "";

interface Version {
  kind: string;
  version: number;
  origin: string;
  pdfPages: number | null;
  factsConfirmed: boolean;
}

async function versions(api: APIRequestContext): Promise<Version[]> {
  return (await (await api.get(`/api/vacancies/${vacancyId}/documents`)).json()) as Version[];
}

function watchErrors(page: Page): string[] {
  const problems: string[] = [];
  page.on("pageerror", (e) => problems.push(e.message));
  page.on("console", (m) => {
    // 404: no resume yet; 409: AI off; 422: a refusal the page shows (a resume too long for two pages).
    if (m.type() === "error" && !/status of 4(04|09|22)/.test(m.text())) {
      problems.push(m.text());
    }
  });
  return problems;
}

const percent = (text: string | null, label: string) => {
  const m = new RegExp(`${label} (\\d+)%`).exec(text ?? "");
  expect(m, `"${label} N%" in "${text}"`).not.toBeNull();
  return Number(m![1]);
};

test.beforeAll(async ({ request, baseURL }) => {
  const ai = (await (await request.get("/api/ai/status")).json()) as { provider: string };
  expect(ai.provider, "start the app with APP_AI_PROVIDER=fake (run-local.sh does)").toBe("fake");
  applyUrl = `${baseURL}/index.html?apply=beta-java-1`;
  const created = await request.post("/api/vacancies", {
    data: { title: TITLE, company: COMPANY, url: applyUrl, description: "We use Java, PostgreSQL, Kafka, Kubernetes.\nThe role is remote." },
  });
  expect(created.status()).toBe(201);
  vacancyId = ((await created.json()) as { id: string }).id;
});

test("the default preset is filled in on the Presets screen", async ({ page }) => {
  const problems = watchErrors(page);
  await page.goto("/#/presets");
  await expect(page.getByRole("heading", { name: "Presets" })).toBeVisible();
  await expect(page.getByLabel("Preset", { exact: true })).toHaveValue(/.+/);

  await page.getByLabel("Main goal prompt").fill("Senior backend roles in payments, remote.\nDeal-breakers: on-site only");
  await page.getByLabel("Base cover letter", { exact: true }).fill("Dear Hiring Manager,\n\nI build payment systems with Java.");
  await page.getByRole("button", { name: "Save preset" }).click();
  await expect(page.getByText("Saved the preset.")).toBeVisible();

  await page.getByLabel("Base resume text", { exact: true }).fill(BASE_RESUME);
  await page.getByRole("button", { name: "Save base resume" }).click();
  await expect(page.getByText("Saved the base resume.")).toBeVisible();
  await expect(page.getByText("ready for AI generation.")).toHaveCount(2);
  expect(problems).toEqual([]);
});

test("both matches show, generation lifts the Employer match, the fact check warns, and the PDF fits", async ({ page, request }) => {
  const problems = watchErrors(page);
  expect((await request.post("/api/ai/consent")).status()).toBe(200);
  await page.goto(`/#/pipeline?open=${vacancyId}`);
  const drawer = page.getByRole("dialog", { name: TITLE });

  // Employer match of the base resume and Goal match, as bars that say their value.
  const employer = drawer.getByRole("meter", { name: "Employer match, base resume" });
  await expect(employer).toHaveAttribute("aria-valuenow", "68");
  await expect(drawer.getByRole("meter", { name: "Goal match" })).toHaveAttribute("aria-valuenow", "70");
  await expect(drawer.getByText(/not a chance of being hired/)).toBeVisible();
  await expect(drawer.getByText(/^Deal-breaker:/)).toHaveCount(0);

  const resumeCard = drawer.getByRole("group", { name: "Resume", exact: true });
  await resumeCard.getByRole("button", { name: "Generate with AI" }).click();
  const comparison = resumeCard.locator(".comparison");
  await expect(comparison).toContainText("Base 68% → Tailored");
  const text = await comparison.textContent();
  const base = percent(text, "Base");
  const tailored = percent(text, "Tailored");
  expect(tailored, `Base ${base}% → Tailored ${tailored}%`).toBeGreaterThan(base);
  await expect(comparison).toContainText("Newly covered: Experience with Kubernetes");
  await expect(drawer.getByRole("list", { name: "Base draft compared with the tailored text" })).toContainText("Kubernetes");

  // The added skill is not in the base resume: saving waits for the owner.
  await expect(drawer.getByText("Not in your base draft.")).toBeVisible();
  await expect(drawer.locator(".facts-list")).toContainText("Kubernetes");
  await expect(drawer.getByRole("button", { name: "Save as version 1" })).toBeDisabled();
  const confirm = drawer.getByLabel("These facts are true and I can back them up in an interview");
  await confirm.check();
  await expect(drawer.getByRole("button", { name: "Save as version 1" })).toBeEnabled();
  // An edit after the confirmation needs a new check and a new confirmation.
  const resumeText = drawer.getByLabel("Resume text");
  await resumeText.fill((await resumeText.inputValue()).replace("by 40%", "by 45%"));
  await expect(drawer.getByRole("button", { name: "Save as version 1" })).toBeDisabled();
  await expect(drawer.locator(".facts-list")).toContainText("45%");
  await expect(confirm).not.toBeChecked();
  await confirm.check();
  await drawer.getByRole("button", { name: "Save as version 1" }).click();
  await expect(resumeCard.getByRole("link", { name: "PDF (1 page)" })).toBeVisible();

  const letterCard = drawer.getByRole("group", { name: "Cover letter", exact: true });
  await letterCard.getByRole("button", { name: "Generate with AI" }).click();
  await expect(letterCard.locator(".comparison")).toContainText(/Base \d+% → Tailored \d+%/);
  await expect(drawer.getByLabel("Cover letter text")).toHaveValue(/I am applying for the Senior Java Engineer role at Beta Bank\./);
  await drawer.getByRole("button", { name: "Save as version 1" }).click();
  await expect(letterCard.getByText(/v1 · AI adaptation, checked by you/)).toBeVisible();

  const saved = await versions(request);
  const resume = saved.find((v) => v.kind === "resume")!;
  expect(resume.pdfPages).toBeGreaterThanOrEqual(1);
  expect(resume.pdfPages).toBeLessThanOrEqual(2);
  expect(resume.factsConfirmed).toBe(true);
  const pdf = await request.get(`/api/documents/${(saved.find((v) => v.kind === "resume") as unknown as { id: string }).id}/download?format=pdf`);
  expect(pdf.headers()["content-type"]).toBe("application/pdf");
  expect((await pdf.body()).subarray(0, 5).toString()).toBe("%PDF-");
  expect(problems).toEqual([]);
});

test("the package carries the documents and I submitted moves the vacancy to Applied", async ({ page, request }) => {
  const problems = watchErrors(page);
  await page.goto(`/#/pipeline?open=${vacancyId}`);
  const drawer = page.getByRole("dialog", { name: TITLE });
  await expect(drawer.getByLabel("Resume version")).not.toHaveValue("");

  const download = page.waitForEvent("download");
  const popup = page.waitForEvent("popup");
  await drawer.getByRole("button", { name: "Prepare package and open the page" }).click();
  expect((await download).suggestedFilename()).toMatch(/^application-Beta-Bank-[0-9a-f]{8}\.zip$/);
  const applyPage = await popup;
  expect(applyPage.url()).toBe(applyUrl);
  await applyPage.close();

  page.once("dialog", (d) => d.accept());
  await drawer.getByRole("button", { name: "I submitted" }).click();
  await expect(drawer.locator(".badge").first()).toContainText("Applied");
  const status = ((await (await request.get(`/api/vacancies/${vacancyId}`)).json()) as { status: string }).status;
  expect(status).toBe("Applied");
  expect(problems).toEqual([]);
});

test("a resume too long for two pages is refused, and Use as is works with AI off", async ({ page, request }) => {
  const problems = watchErrors(page);
  const bullets = Array.from({ length: 300 }, (_, i) => `- Delivered improvement number ${i + 1} to the payment platform with Java.`);
  const long = (await request.post("/api/presets", { data: { name: "Long resume" } })).json() as Promise<{ id: string }>;
  const longId = (await long).id;
  expect((await request.patch(`/api/presets/${longId}`, { data: { mainGoal: "Backend roles." } })).status()).toBe(200);
  expect(
    (await request.post(`/api/presets/${longId}/base-resume/text`, { data: { text: `# Jane Doe\n\n## Experience\n${bullets.join("\n")}` } })).status(),
  ).toBe(200);
  expect((await request.delete("/api/ai/consent")).status()).toBe(200);

  await page.goto(`/#/pipeline?open=${vacancyId}`);
  const drawer = page.getByRole("dialog", { name: TITLE });
  const resumeCard = drawer.getByRole("group", { name: "Resume", exact: true });
  await expect(resumeCard.getByRole("button", { name: "Generate with AI" })).toBeDisabled();

  const before = (await versions(request)).length;
  await drawer.getByLabel("Preset for the documents").selectOption({ label: "Long resume" });
  await resumeCard.getByRole("button", { name: "Use as is" }).click();
  await expect(drawer.getByRole("alert").filter({ hasText: "at most 2 are allowed" })).toBeVisible();
  expect((await versions(request)).length, "nothing too long is saved").toBe(before);

  // Back to the default preset: its base resume is used as it is, with AI off, and gets its PDF.
  await drawer.getByLabel("Preset for the documents").selectOption({ index: 0 });
  await resumeCard.getByRole("button", { name: "Use as is" }).click();
  await expect(resumeCard.getByText(/v2 · base draft as is/)).toBeVisible();
  const asIs = (await versions(request)).find((v) => v.kind === "resume" && v.version === 2)!;
  expect(asIs.origin).toBe("base");
  expect(asIs.pdfPages).toBe(1);
  expect(problems).toEqual([]);
});
