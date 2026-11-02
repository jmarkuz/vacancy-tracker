import { expect, test, type APIRequestContext, type Page } from "@playwright/test";

/**
 * The whole application cycle through the UI (T-091), with fake data, the fake AI provider (the app runs with
 * APP_AI_PROVIDER=fake) and a test letter imported as an .eml file: a vacancy, its fit to the resume, tailored
 * documents edited before saving, the application package and the apply page, "I submitted" (Applied), the
 * employer's answer and the suggested status change. Nothing leaves the machine. Runs after the smoke test.
 */
test.describe.configure({ mode: "serial" });

const TITLE = "Backend Engineer";
const COMPANY = "Acme Pay";
let vacancyId = "";
let applyUrl = "";

async function vacancy(api: APIRequestContext): Promise<{ status: string }> {
  return (await (await api.get(`/api/vacancies/${vacancyId}`)).json()) as { status: string };
}

function watchErrors(page: Page): string[] {
  const problems: string[] = [];
  page.on("pageerror", (e) => problems.push(e.message));
  page.on("console", (m) => {
    // 404 is the normal answer for "no resume yet"; 409 for "AI is off" before the owner switches it on.
    if (m.type() === "error" && !/status of 40[149]/.test(m.text())) {
      problems.push(m.text());
    }
  });
  return problems;
}

test.beforeAll(async ({ request, baseURL }) => {
  const ai = (await (await request.get("/api/ai/status")).json()) as { provider: string };
  expect(ai.provider, "start the app with APP_AI_PROVIDER=fake (run-local.sh does)").toBe("fake");
  applyUrl = `${baseURL}/index.html?apply=acme-backend-1`;
  const created = await request.post("/api/vacancies", {
    data: {
      title: TITLE,
      company: COMPANY,
      url: applyUrl,
      description:
        "Requirements:\n- 3+ years with Java, Spring Boot, PostgreSQL\n- Kafka, Docker and AWS are a plus\nVisa sponsorship available.",
    },
  });
  expect(created.status()).toBe(201);
  vacancyId = ((await created.json()) as { id: string }).id;
});

test("the resume is uploaded and the vacancy shows its fit with the reasons", async ({ page }) => {
  const problems = watchErrors(page);
  await page.goto("/#/resume");
  await page.getByLabel("Resume file").setInputFiles({
    name: "jane.md",
    mimeType: "text/markdown",
    buffer: Buffer.from("Jane Doe\n\nSkills\nJava, Spring Boot, PostgreSQL, Kafka\n\nAcme Bank, 2019 - 2025\n"),
  });
  await expect(page.getByRole("list", { name: "Keywords" }).getByText("Spring Boot")).toBeVisible();

  await page.goto(`/#/pipeline?open=${vacancyId}`);
  const drawer = page.getByRole("dialog", { name: TITLE });
  await expect(drawer.getByRole("heading", { name: "How this vacancy fits" })).toBeVisible();
  await expect(drawer.getByText(/a quick estimate from your resume keywords, without AI/)).toBeVisible();
  await expect(drawer.locator(".reasons")).toContainText("Matches");
  await expect(drawer.locator(".reasons")).toContainText("Not in your resume: Docker");
  await expect(drawer.getByText("Offered")).toBeVisible(); // visa sponsorship read from the text
  expect(problems).toEqual([]);
});

test("AI stays off until switched on, then drafts are edited and saved as versions", async ({ page, request }) => {
  const problems = watchErrors(page);
  // The default preset gets a goal and a base cover letter (M7); its base resume is the resume uploaded above.
  const presets = (await (await request.get("/api/presets")).json()) as { id: string }[];
  expect(
    (await request.patch(`/api/presets/${presets[0].id}`, {
      data: { mainGoal: "Backend roles in payments.", baseCoverLetter: "Dear Hiring Manager,\n\nI build payment systems." },
    })).status(),
  ).toBe(200);
  await page.goto(`/#/pipeline?open=${vacancyId}`);
  const drawer = page.getByRole("dialog", { name: TITLE });
  const resumeCard = drawer.getByRole("group", { name: "Resume", exact: true });
  const letterCard = drawer.getByRole("group", { name: "Cover letter", exact: true });
  await expect(resumeCard.getByRole("button", { name: "Generate with AI" })).toBeDisabled();

  await page.goto("/#/settings");
  await expect(page.getByText("Fake provider for tests")).toBeVisible();
  await page.getByRole("checkbox").check();
  await page.getByRole("button", { name: "Switch AI on" }).click();
  await expect(page.locator(".state-badge")).toHaveText("On");

  await page.goto(`/#/pipeline?open=${vacancyId}`);
  await resumeCard.getByRole("button", { name: "Generate with AI" }).click();
  const resumeText = drawer.getByLabel("Resume text");
  // An adaptation of the base resume: the fake adds the two vacancy skills it lacks, and the fact check catches them.
  await expect(resumeText).toHaveValue(/^Jane Doe[\s\S]*Built production services with Docker and AWS\./);
  await resumeText.fill(`${await resumeText.inputValue()}\nChecked by Jane.`);
  await expect(drawer.getByText("Not in your base draft.")).toBeVisible();
  await expect(drawer.getByRole("button", { name: "Save as version 1" })).toBeDisabled();
  await drawer.getByLabel("These facts are true and I can back them up in an interview").check();
  await drawer.getByRole("button", { name: "Save as version 1" }).click();
  await expect(resumeCard.getByText(/v1 · AI adaptation, checked by you/)).toBeVisible();

  await letterCard.getByRole("button", { name: "Generate with AI" }).click();
  await expect(drawer.getByLabel("Cover letter text")).toHaveValue(/I am applying for the Backend Engineer role at Acme Pay\./);
  await drawer.getByRole("button", { name: "Save as version 1" }).click();
  await expect(letterCard.getByText(/v1 · AI adaptation, checked by you/)).toBeVisible();
  expect(problems).toEqual([]);
});

test("the package downloads, the apply page opens, and I submitted moves the vacancy to Applied", async ({ page, request }) => {
  const problems = watchErrors(page);
  await page.goto(`/#/pipeline?open=${vacancyId}`);
  const drawer = page.getByRole("dialog", { name: TITLE });
  await expect(drawer.getByRole("heading", { name: "Apply" })).toBeVisible();
  await expect(drawer.getByLabel("Resume version")).not.toHaveValue("");
  await expect(drawer.getByLabel("Cover letter version")).not.toHaveValue("");

  const download = page.waitForEvent("download");
  const popup = page.waitForEvent("popup");
  await drawer.getByRole("button", { name: "Prepare package and open the page" }).click();
  expect((await download).suggestedFilename()).toMatch(/^application-Acme-Pay-[0-9a-f]{8}\.zip$/);
  const applyPage = await popup;
  expect(applyPage.url()).toBe(applyUrl);
  await applyPage.close();
  expect((await vacancy(request)).status, "nothing is submitted by the app").toBe("New");

  page.once("dialog", (d) => d.accept());
  await drawer.getByRole("button", { name: "I submitted" }).click();
  await expect(drawer.locator(".badge").first()).toContainText("Applied");
  expect((await vacancy(request)).status).toBe("Applied");
  await expect(drawer.getByText(/applied with package [0-9a-f]{8}, resume v1, cover letter v1/)).toBeVisible();
  expect(problems).toEqual([]);
});

test("the employer's answer is linked to the application and its suggested status is accepted", async ({ page, request }) => {
  const problems = watchErrors(page);
  await page.goto("/#/mail");
  const eml = [
    "From: Acme Pay Recruiting <talent@acmepay.example>",
    "To: jane@example.test",
    "Subject: Interview invitation: Backend Engineer",
    "Message-ID: <e2e-interview-1@acmepay.example>",
    "Content-Type: text/plain; charset=UTF-8",
    "",
    "Hi Jane,",
    "thank you for applying. We would like to invite you to an interview next week.",
    "",
  ].join("\r\n");
  await page.getByLabel("Email file to import").setInputFiles({ name: "answer.eml", mimeType: "message/rfc822", buffer: Buffer.from(eml) });

  const item = page.locator(".mail-item", { hasText: "Interview invitation: Backend Engineer" });
  await expect(item).toBeVisible();
  await expect(item.locator(".mail-category")).toHaveText("Interview");
  await expect(item).toContainText("The company name is in the sender's name");
  await expect(item).toContainText("Move to Interview?");

  await item.getByRole("button", { name: "Accept" }).click();
  await expect(page.getByText("Moved to Interview.")).toBeVisible();
  expect((await vacancy(request)).status).toBe("Interview");

  const history = (await (await request.get(`/api/vacancies/${vacancyId}/history`)).json()) as { to: string; note: string | null }[];
  expect(history.map((h) => h.to)).toEqual(["New", "Applied", "Screening", "Interview"]);
  expect(history[3].note).toBe("from email: Interview invitation: Backend Engineer");
  expect(problems).toEqual([]);
});
