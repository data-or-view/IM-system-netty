import { defineConfig } from "@playwright/test";

const WEB_BASE_URL = "http://localhost:39073";

export default defineConfig({
  testDir: "./e2e",
  timeout: 30000,
  expect: {
    timeout: 8000,
  },
  use: {
    baseURL: WEB_BASE_URL,
    headless: true,
    screenshot: "only-on-failure",
  },
  webServer: {
    command: "npm run dev",
    url: WEB_BASE_URL,
    reuseExistingServer: true,
  },
});
