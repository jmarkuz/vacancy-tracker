import { expect, test } from "@playwright/test";

/**
 * The Inbox filter (T-100): words in the title or description, all or any of them, and words to exclude, applied by
 * the server and kept in the address. Runs after the other browser tests on the same database; its vacancies carry
 * made-up words, so the vacancies of the earlier tests never match.
 */
test.describe.configure({ mode: "serial" });

test.beforeAll(async ({ request }) => {
  for (const [title, description] of [
    ["Java backend engineer", "zqtoken Java and zqkafka in fintech."],
    ["Data engineer", "zqtoken zqpython pipelines."],
    ["Java exchange developer", "zqtoken Java at a crypto exchange."],
  ]) {
    expect((await request.post("/api/vacancies", { data: { title, company: "Filter Co", description } })).status()).toBe(201);
  }
});

test("the Inbox is filtered by words, all or any, and by words to exclude", async ({ page }) => {
  await page.goto("/#/inbox");
  const list = page.getByRole("listbox", { name: "New vacancies" });
  await expect(list).toBeVisible();

  // "/" jumps to the filter; every word must be there by default.
  await page.keyboard.press("/");
  const words = page.getByLabel("Words in the title or description");
  await expect(words).toBeFocused();
  await words.fill("zqtoken java");
  await expect(list.getByRole("option")).toHaveCount(2);
  await expect(page).toHaveURL(/q=zqtoken\+java/);
  await expect(page.getByText(/2 new matching the filter/)).toBeVisible();

  await page.getByLabel("Words to exclude").fill("crypto");
  await expect(list.getByRole("option")).toHaveCount(1);
  await expect(list.getByRole("option").first()).toContainText("Java backend engineer");

  // Any of the words.
  await page.getByLabel("Words to exclude").fill("");
  await words.fill("zqkafka zqpython");
  await page.getByLabel("How the words must match").selectOption("any");
  await expect(list.getByRole("option")).toHaveCount(2);
  await expect(page).toHaveURL(/any=1/);

  // Nothing matches: the empty state offers to clear; clearing brings the whole Inbox back.
  await words.fill("zqnothing");
  await expect(page.getByRole("heading", { name: "No new vacancy matches the filter" })).toBeVisible();
  await page.getByRole("button", { name: "Clear the filter" }).click();
  await expect(words).toHaveValue("");
  await expect(list.getByRole("option").filter({ hasText: "Data engineer" })).toHaveCount(1);
  await expect(page).not.toHaveURL(/q=/);
});
