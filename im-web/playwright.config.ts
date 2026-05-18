import { defineConfig } from "@playwright/test";

export default defineConfig({
  testDir: "./e2e",
  timeout: 30000,
  expect: {
    timeout: 8000,
  },
  use: {
    baseURL: "http://localhost:8083",
    headless: true,
    screenshot: "only-on-failure",
  },
  webServer: {
    command: "npm run dev",
    url: "http://localhost:8083",
    reuseExistingServer: true,
  },
});
