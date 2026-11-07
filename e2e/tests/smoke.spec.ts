import { expect, test, type APIRequestContext } from "@playwright/test";

/**
 * The main path through the app on an empty database: first run, a profile from the wizard, the Inbox with the
 * keyboard, the dashboard, the board and the command palette. The tests depend on each other and run in order.
 */
test.describe.configure({ mode: "serial" });

async function status(api: APIRequestContext, title: string): Promise<string | undefined> {
  const response = await api.get("/api/vacancies?size=50");
  const body = (await response.json()) as { items: { title: string; status: string }[] };
  return body.items.find((v) => v.title === title)?.status;
}

test.beforeAll(async ({ request }) => {
  const health = await request.get("/actuator/health");
  expect(health.ok(), "the app must be running at BASE_URL").toBeTruthy();
  const profiles = (await (await request.get("/api/profiles")).json()) as unknown[];
  expect(profiles, "the smoke test needs an empty database").toHaveLength(0);
  for (const [title, company] of [["Smoke test engineer", "Acme"], ["Unrelated clerk", "Other Inc"]]) {
    const created = await request.post("/api/vacancies", { data: { title, company } });
    expect(created.status()).toBe(201);
    if (title === "Unrelated clerk") {
      // A vacancy the user adds is never hidden by automation, so the one that must stay out of the Inbox is dismissed.
      const id = ((await created.json()) as { id: string }).id;
      const dismissed = await request.post(`/api/vacancies/${id}/status`, { data: { status: "Dismissed" } });
      expect(dismissed.ok()).toBe(true);
    }
  }
});

test.beforeEach(async ({ page }) => {
  const problems: string[] = [];
  page.on("pageerror", (e) => problems.push(e.message));
  page.on("console", (m) => {
    if (m.type() === "error" && !/status of 40[14]/.test(m.text())) {
      problems.push(m.text());
    }
  });
  test.info().annotations.push({ type: "problems", description: "checked in afterEach" });
  (page as unknown as { __problems: string[] }).__problems = problems;
});

test.afterEach(async ({ page }) => {
  expect((page as unknown as { __problems: string[] }).__problems, "no script errors in the browser").toEqual([]);
});

test("first run leads to the wizard, which creates the first profile", async ({ page, request }) => {
  await page.goto("/");
  await expect(page).toHaveURL(/#\/profiles\?wizard=1/);
  await expect(page.getByRole("heading", { name: "Set up your first search profile" })).toBeVisible();
  expect(await (await request.get("/api/profiles")).json(), "nothing is created before the user answers").toHaveLength(0);

  await page.getByLabel("1. Name this profile").fill("Smoke profile");
  await page.getByLabel(/2\. What are you looking for/).fill("smoke");
  await page.getByRole("button", { name: "Create profile" }).click();

  await expect(page.getByRole("form", { name: "Profile editor" })).toBeVisible();
  await expect(page.getByLabel("Label").first(), "the wizard's rule is shown for review").toHaveValue("Looked-for words");
  const profiles = (await (await request.get("/api/profiles")).json()) as { name: string; criteria: unknown[] }[];
  expect(profiles).toHaveLength(1);
  expect(profiles[0].name).toBe("Smoke profile");
  expect(profiles[0].criteria).toHaveLength(1);
});

test("the dashboard counts the vacancy that matches and not the one that does not", async ({ page, request }) => {
  // Saving the profile starts a background rescore; the engineer gets a score once it has run.
  await expect.poll(async () => {
    const d = (await (await request.get("/api/dashboard")).json()) as { topMatches: { title: string }[] };
    return d.topMatches.map((m) => m.title);
  }, { timeout: 15_000 }).toContain("Smoke test engineer");
  expect(await status(request, "Smoke test engineer")).toBe("New");
  expect(await status(request, "Unrelated clerk")).toBe("Dismissed");

  await page.goto("/#/dashboard");
  await expect(page.getByRole("heading", { name: "Dashboard" })).toBeVisible();
  await expect(page.locator('[data-kpi="inbox"] .value')).toHaveText("1");
  await expect(page.getByRole("list", { name: "Top matches" })).toContainText("Smoke test engineer");
});

test("the Inbox is triaged with the keyboard", async ({ page, request }) => {
  await page.goto("/#/inbox");
  const row = page.getByRole("option", { name: /Smoke test engineer/ });
  await expect(row).toBeVisible();

  await page.keyboard.press("s");

  await expect(page.getByRole("status").filter({ hasText: "Saved: Smoke test engineer" })).toBeVisible();
  await expect.poll(async () => status(request, "Smoke test engineer")).toBe("Saved");
});

test("the board moves a card with its list and records the change", async ({ page, request }) => {
  await page.goto("/#/pipeline?layout=board");
  const saved = page.locator('section[data-column="Saved"]');
  await expect(saved.getByRole("button", { name: "Smoke test engineer", exact: true })).toBeVisible();

  await page.getByLabel("Move Smoke test engineer to").selectOption("Applied");
  // Choosing alone changes nothing: the move needs the button (review 2026-11-02).
  expect(await status(request, "Smoke test engineer")).toBe("Saved");
  await page.getByRole("button", { name: "Move Smoke test engineer", exact: true }).click();

  await expect(page.locator('section[data-column="Applied"]').getByRole("button", { name: "Smoke test engineer", exact: true })).toBeVisible();
  expect(await status(request, "Smoke test engineer")).toBe("Applied");
  const list = (await (await request.get("/api/vacancies?size=50")).json()) as { items: { id: string; title: string }[] };
  const id = list.items.find((v) => v.title === "Smoke test engineer")!.id;
  const history = (await (await request.get(`/api/vacancies/${id}/history`)).json()) as { to: string }[];
  expect(history.map((e) => e.to)).toEqual(expect.arrayContaining(["Saved", "Applied"]));
});

test("the command palette finds a vacancy and opens it", async ({ page }) => {
  await page.goto("/#/dashboard");
  const box = page.getByRole("combobox", { name: /Search vacancies/ });
  // The shortcut's listener is attached in an effect after the first render, which can come after the page's load
  // event on a busy runner: a key pressed before that is lost. So press only while the palette is not open, wait for it
  // to show each time, and try again; the check before each press keeps a late open from being toggled shut.
  await expect(async () => {
    if (!(await box.isVisible())) {
      await page.keyboard.press("Control+k");
    }
    await expect(box).toBeVisible({ timeout: 1500 });
  }).toPass({ timeout: 15_000 });
  await expect(box).toBeFocused();

  await box.fill("smoke");
  const option = page.getByRole("option", { name: /Smoke test engineer/ });
  await expect(option).toBeVisible();
  await page.keyboard.press("Enter");

  await expect(page).toHaveURL(/#\/pipeline\?open=/);
  // The drawer is a dialog named by the vacancy title; the sidebar is an aside too, so a looser query always passed.
  await expect(page.getByRole("dialog", { name: "Smoke test engineer" })).toBeVisible();
});

test("every main screen loads", async ({ page }) => {
  for (const [path, heading] of [
    ["#/inbox", "Inbox"], ["#/pipeline", "Pipeline"], ["#/sources", "Sources & Runs"], ["#/profiles", "Search profiles"],
  ] as const) {
    await page.goto("/" + path);
    await expect(page.getByRole("heading", { name: heading, exact: true }).first()).toBeVisible();
  }
});
