import { expect, test } from "@playwright/test";

/**
 * Beacon design (T-101): the Inbox shows the selected vacancy beside the list with its tabs and a "Save vacancy"
 * button that is always there; the theme and the text size come from Settings; the page can be read and used at
 * 640 px with 22 px text without scrolling sideways. Runs last, on the same database; its vacancies carry made-up words.
 */
test.describe.configure({ mode: "serial" });

test.beforeAll(async ({ request }) => {
  for (const [title, description] of [
    ["Beacon backend engineer", "zqbeacon Java and Kafka."],
    ["Beacon data engineer", "zqbeacon Python."],
  ]) {
    expect((await request.post("/api/vacancies", { data: { title, company: "Beacon Co", description } })).status()).toBe(201);
  }
});

test("the Inbox shows the selected vacancy beside the list, with keyboard tabs and Save vacancy", async ({ page, request }) => {
  await page.goto("/#/inbox?q=zqbeacon");
  const list = page.getByRole("listbox", { name: "New vacancies" });
  await expect(list.getByRole("option")).toHaveCount(2);
  await list.getByRole("option", { name: /Beacon backend engineer/ }).click();

  const inspector = page.getByRole("complementary", { name: "Selected vacancy: Beacon backend engineer" });
  await expect(inspector).toBeVisible();
  await expect(page.getByRole("dialog")).toHaveCount(0); // no overlay: the list stays usable

  const conditions = inspector.getByRole("tab", { name: "Conditions" });
  await expect(conditions).toHaveAttribute("aria-selected", "true");
  await expect(inspector.getByRole("tabpanel")).toContainText("Salary");
  await expect(inspector.getByRole("tabpanel")).toContainText("Not specified");
  await conditions.focus();
  await page.keyboard.press("ArrowRight");
  await expect(inspector.getByRole("tab", { name: "Match" })).toHaveAttribute("aria-selected", "true");
  await expect(inspector.getByRole("tab", { name: "Match" })).toBeFocused();
  await page.keyboard.press("End");
  await expect(inspector.getByRole("tab", { name: "Documents" })).toHaveAttribute("aria-selected", "true");
  await page.keyboard.press("Home");
  await expect(conditions).toHaveAttribute("aria-selected", "true");

  await inspector.getByRole("button", { name: "Save vacancy" }).click();
  await expect(list.getByRole("option")).toHaveCount(1);
  const saved = (await (await request.get("/api/vacancies?status=Saved&size=100")).json()) as { items: { title: string }[] };
  expect(saved.items.map((v) => v.title)).toContain("Beacon backend engineer");

  await page.getByRole("complementary", { name: /Selected vacancy: Beacon data engineer/ }).getByRole("button", { name: "Skip" }).click();
  await expect(page.getByRole("heading", { name: "No new vacancy matches the filter" })).toBeVisible();
});

test("a document saved in the full details shows in the Inbox panel at once", async ({ page, request }) => {
  const created = await request.post("/api/vacancies", { data: { title: "Beacon documents role", company: "Beacon Co", description: "zqbeacondocs Java." } });
  expect(created.status()).toBe(201);
  await page.goto("/#/inbox?q=zqbeacondocs");
  const inspector = page.getByRole("complementary", { name: "Selected vacancy: Beacon documents role" });
  await inspector.getByRole("tab", { name: "Documents" }).click();
  await expect(inspector.getByRole("tabpanel")).toContainText("Resume: none yet");

  await inspector.getByRole("button", { name: "Full details" }).click();
  const drawer = page.getByRole("dialog", { name: "Beacon documents role" });
  await drawer.getByRole("group", { name: "Resume", exact: true }).getByRole("button", { name: "Use as is" }).click();
  await expect(drawer.getByRole("group", { name: "Resume", exact: true })).toContainText("v1 · base draft as is");
  await page.keyboard.press("Escape");
  await expect(drawer).toHaveCount(0);

  await expect(inspector.getByRole("tabpanel")).toContainText("Resume: version 1");
});

test("the sections carry their color role and the current one is marked, and Skip to content works", async ({ page }) => {
  await page.goto("/#/presets");
  const current = page.getByRole("link", { name: "Presets" });
  await expect(current).toHaveAttribute("aria-current", "page");
  await expect(current).toHaveAttribute("data-role", "purple");
  await expect(page.getByRole("link", { name: "Pipeline" })).toHaveAttribute("data-role", "green");
  await expect(page.getByRole("link", { name: "Sources & Runs" })).toHaveAttribute("data-role", "amber");

  await page.keyboard.press("Tab");
  await expect(page.getByRole("link", { name: "Skip to content" })).toBeFocused();
  await page.keyboard.press("Enter");
  await expect(page.locator("#content")).toBeFocused();
});

test("theme and text size from Settings apply everywhere and stay, and 640 px with 22 px text needs no sideways scroll", async ({ page }) => {
  await page.goto("/#/settings");
  await expect(page.locator("html")).toHaveAttribute("data-theme", "light");
  await expect.poll(() => page.evaluate(() => getComputedStyle(document.documentElement).fontSize)).toBe("18px");

  await page.getByLabel("Dark", { exact: true }).check();
  await page.getByLabel("22 px").check();
  await expect(page.locator("html")).toHaveAttribute("data-theme", "dark");
  await expect.poll(() => page.evaluate(() => getComputedStyle(document.documentElement).fontSize)).toBe("22px");

  await page.reload();
  await expect(page.locator("html")).toHaveAttribute("data-theme", "dark");
  await expect(page.locator("html")).toHaveAttribute("data-text-size", "22");

  await page.setViewportSize({ width: 640, height: 800 });
  for (const screen of ["inbox", "pipeline", "presets", "settings"]) {
    await page.goto(`/#/${screen}`);
    await expect(page.locator("#content")).toBeVisible();
    const sideways = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
    expect(sideways, `${screen} scrolls sideways`).toBeLessThanOrEqual(0);
  }

  // Back to the defaults for anyone using this browser profile afterwards.
  await page.goto("/#/settings");
  await page.getByLabel("Light", { exact: true }).check();
  await page.getByLabel("18 px (standard)").check();
  await expect(page.locator("html")).toHaveAttribute("data-theme", "light");
});
