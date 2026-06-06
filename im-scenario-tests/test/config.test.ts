import test from "node:test";
import assert from "node:assert/strict";
import { loadScenarioConfig } from "../src/config.js";

test("loadScenarioConfig uses local development defaults", () => {
  const config = loadScenarioConfig({});

  assert.equal(config.httpUrl, "http://127.0.0.1:8084");
  assert.equal(config.wsUrl, "ws://127.0.0.1:8083/ws");
  assert.equal(config.defaultPassword, "123456");
  assert.equal(config.requestTimeoutMs, 5_000);
});

test("loadScenarioConfig reads environment overrides", () => {
  const config = loadScenarioConfig({
    IM_SCENARIO_HTTP_URL: "http://localhost:18084",
    IM_SCENARIO_WS_URL: "ws://localhost:18081/ws",
    IM_SCENARIO_PASSWORD: "pw",
    IM_SCENARIO_TIMEOUT_MS: "9000",
  });

  assert.equal(config.httpUrl, "http://localhost:18084");
  assert.equal(config.wsUrl, "ws://localhost:18081/ws");
  assert.equal(config.defaultPassword, "pw");
  assert.equal(config.requestTimeoutMs, 9_000);
});
