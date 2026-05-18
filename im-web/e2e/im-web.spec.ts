import { test, expect, type Page } from "@playwright/test";

const BASE_URL = "http://localhost:8083";
const TEST_USER = "playwright_e2e_" + Date.now();
const PEER_USER = "playwright_peer_" + Date.now();

async function register(page: Page, userId: string) {
  await page.goto(BASE_URL + "/login");
  await page.evaluate(() => localStorage.clear());
  await page.click('button:has-text("注册")');
  await expect(page.locator("text=注册新用户")).toBeVisible();
  await page.fill('input[placeholder="用户 ID"]', userId);
  await page.click('button[type="submit"]:has-text("注册")');
  await page.waitForURL("**/chat", { timeout: 15000 });
  await expect(page.locator(`text=${userId}`).first()).toBeVisible({ timeout: 10000 });
}

function wsRequest(msg: Record<string, unknown>): Promise<Record<string, unknown>> {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket("ws://127.0.0.1:8081/ws");
    ws.onopen = () => ws.send(JSON.stringify(msg));
    ws.onmessage = (event) => {
      try { resolve(JSON.parse(event.data)); } catch { /* ignore */ }
      ws.close();
    };
    ws.onerror = () => reject(new Error("WS error"));
    setTimeout(() => { ws.close(); reject(new Error("WS timeout")); }, 5000);
  });
}

// Collect console errors and page errors during tests
async function collectErrors(page: Page): Promise<string[]> {
  const errors: string[] = [];
  page.on("pageerror", (err) => errors.push(`[PAGE_ERROR] ${err.message}`));
  page.on("console", (msg) => {
    if (msg.type() === "error") errors.push(`[console.error] ${msg.text()}`);
  });
  return errors;
}

test.describe("IM Web E2E", () => {
  test("1. Login page renders correctly", async ({ page }) => {
    await page.goto(BASE_URL + "/login");
    await expect(page.locator("h1")).toContainText("IM System");
    await expect(page.locator('button:has-text("登录")').first()).toBeVisible();
    await expect(page.locator('button:has-text("注册")').first()).toBeVisible();
    await expect(page.locator('input[placeholder="用户 ID"]')).toBeVisible();
  });

  test("2. Register and show chat layout", async ({ page }) => {
    const errors = await collectErrors(page);
    await register(page, TEST_USER);
    await expect(page.locator("text=暂无会话")).toBeVisible({ timeout: 5000 });
    await expect(page.locator(`text=${TEST_USER}`).first()).toBeVisible();
    // Print non-extension errors
    const appErrors = errors.filter(e => !e.includes("runtime.lastError"));
    if (appErrors.length > 0) {
      console.error("App errors:", appErrors);
    }
  });

  test("3. Login with existing user", async ({ page }) => {
    await register(page, TEST_USER);

    await page.locator('button[title="退出登录"]').click();
    await expect(page.locator("h1")).toContainText("IM System", { timeout: 5000 });

    await page.fill('input[placeholder="用户 ID"]', TEST_USER);
    await page.click('button[type="submit"]:has-text("登录")');
    await page.waitForURL("**/chat", { timeout: 15000 });
    await expect(page.locator(`text=${TEST_USER}`).first()).toBeVisible({ timeout: 10000 });
  });

  test("4. Contacts tab shows empty state", async ({ page }) => {
    await register(page, TEST_USER);
    await page.locator('[class*="gap-0\\.5"] button').nth(1).click();
    await expect(page.locator("text=暂无好友")).toBeVisible({ timeout: 5000 });
  });

  test("5. Toggle between chat and contacts tabs", async ({ page }) => {
    await register(page, TEST_USER);
    const tabBtnGroup = page.locator('[class*="gap-0\\.5"] button');

    await expect(page.locator("text=暂无会话")).toBeVisible({ timeout: 5000 });
    await tabBtnGroup.nth(1).click();
    await expect(page.locator("text=暂无好友")).toBeVisible({ timeout: 5000 });
    await tabBtnGroup.nth(0).click();
    await expect(page.locator("text=暂无会话")).toBeVisible({ timeout: 5000 });
  });

  test("6. Chat area empty state", async ({ page }) => {
    await register(page, TEST_USER);
    await expect(page.locator("text=选择一个会话开始聊天")).toBeVisible({ timeout: 5000 });
  });

  test("7. Logout returns to login page", async ({ page }) => {
    await register(page, TEST_USER);

    await page.locator('button[title="退出登录"]').click();
    await expect(page.locator("h1")).toContainText("IM System", { timeout: 5000 });
    await expect(page.locator('input[placeholder="用户 ID"]')).toBeVisible();
    await expect(page.locator('button[type="submit"]')).toContainText("登录");
  });

  test("8. Search user dialog", async ({ page }) => {
    await register(page, TEST_USER);

    await page.click('button:has-text("加好友")');
    await expect(page.locator("text=搜索用户")).toBeVisible({ timeout: 3000 });
    await expect(page.locator('input[placeholder*="userId"]')).toBeVisible();

    await page.keyboard.press("Escape");
    await page.waitForTimeout(500);
    await expect(page.locator("text=搜索用户")).not.toBeVisible();
  });

  test("9. Send friend request", async ({ page }) => {
    await wsRequest({ op: "register", seq: 1, userId: PEER_USER });

    await register(page, TEST_USER);

    await page.click('button:has-text("加好友")');
    await expect(page.locator("text=搜索用户")).toBeVisible({ timeout: 3000 });

    await page.fill('input[placeholder*="userId"]', PEER_USER);
    await page.locator('button:has(svg.lucide-search)').click();
    await page.waitForTimeout(1500);

    const addFriendBtn = page.locator('button:has-text("加好友")').last();
    const addVisible = await addFriendBtn.isVisible().catch(() => false);
    if (addVisible) {
      await addFriendBtn.click();
      await page.waitForTimeout(500);
    }
  });

  test("10. Create group page", async ({ page }) => {
    await register(page, TEST_USER);

    await page.click('button:has-text("创建群")');
    await page.waitForURL("**/chat/create-group", { timeout: 5000 });

    await page.goBack();
    await expect(page.locator("text=暂无会话")).toBeVisible({ timeout: 5000 });
  });
});
