import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import { loadScenarioConfig } from "../src/config.js";
import { CLUSTER_NODE_DEFAULTS, SCENARIO_DEFAULTS } from "../src/defaults.js";

test("loadScenarioConfig uses local development defaults", () => {
  const config = loadScenarioConfig({});

  assert.equal(config.httpUrl, SCENARIO_DEFAULTS.httpUrl);
  assert.equal(config.wsUrl, SCENARIO_DEFAULTS.wsUrl);
  assert.equal(config.defaultPassword, SCENARIO_DEFAULTS.defaultPassword);
  assert.equal(config.requestTimeoutMs, SCENARIO_DEFAULTS.requestTimeoutMs);
  assert.equal(config.pollIntervalMs, SCENARIO_DEFAULTS.pollIntervalMs);
});

test("loadScenarioConfig reads environment overrides", () => {
  const config = loadScenarioConfig({
    IM_SCENARIO_HTTP_URL: "http://localhost:18084",
    IM_SCENARIO_WS_URL: "ws://localhost:18081/ws",
    IM_SCENARIO_PASSWORD: "pw",
    IM_SCENARIO_TIMEOUT_MS: "9000",
    IM_SCENARIO_POLL_INTERVAL_MS: "250",
  });

  assert.equal(config.httpUrl, "http://localhost:18084");
  assert.equal(config.wsUrl, "ws://localhost:18081/ws");
  assert.equal(config.defaultPassword, "pw");
  assert.equal(config.requestTimeoutMs, 9_000);
  assert.equal(config.pollIntervalMs, 250);
});

test("package scripts expose P0 scenario layers", () => {
  const packageJson = JSON.parse(fs.readFileSync(new URL("../../package.json", import.meta.url), "utf8"));

  assert.equal(typeof packageJson.scripts["scenario:smoke"], "string");
  assert.equal(typeof packageJson.scripts["scenario:core"], "string");
  assert.equal(typeof packageJson.scripts["scenario:p0"], "string");
  assert.equal(typeof packageJson.scripts["scenario:ci"], "string");
  assert.equal(typeof packageJson.scripts["scenario:full"], "string");
  assert.equal(typeof packageJson.scripts["scenario:chaos"], "string");
  assert.match(packageJson.scripts["scenario:p0"], /smoke/);
  assert.match(packageJson.scripts["scenario:p0"], /cluster-ha/);
  assert.match(packageJson.scripts["scenario:p0"], new RegExp(new URL(CLUSTER_NODE_DEFAULTS.node1.httpUrl).port));
  assert.match(packageJson.scripts["scenario:p0"], new RegExp(new URL(CLUSTER_NODE_DEFAULTS.node1.wsUrl).port));
  assert.match(packageJson.scripts["scenario:core:compiled"], /offline-sync/);
  assert.match(packageJson.scripts["scenario:chaos:compiled"], /message-idempotency/);
  assert.match(packageJson.scripts["scenario:full"], /scenario:all:compiled/);
});

test("release scenarios keep their success control, stable rejection contract, and shutdown authorization", () => {
  const packageJson = JSON.parse(fs.readFileSync(new URL("../../package.json", import.meta.url), "utf8"));
  const uploadScenario = fs.readFileSync(new URL("../../scenarios/file-upload-policy.ts", import.meta.url), "utf8");
  const clusterScenario = fs.readFileSync(new URL("../../scenarios/cluster-ha.ts", import.meta.url), "utf8");
  const guide = fs.readFileSync(new URL("../../../docs/ai-project-guide.md", import.meta.url), "utf8");

  assert.match(uploadScenario, /download\/sign[\s\S]*fileId:\s*exactPolicy\.fileId/);
  assert.match(uploadScenario, /fileUrl/);
  assert.match(clusterScenario, /result\.reason instanceof ScenarioHttpError[\s\S]*httpStatus === 403[\s\S]*code === 403/);
  assert.doesNotMatch(clusterScenario, /group call is full/);
  for (const scriptName of ["scenario:p0", "scenario:full"] as const) {
    assert.doesNotMatch(packageJson.scripts[scriptName], /IM_SCENARIO_NODE1_PID_FILE/);
  }
  assert.match(guide, /IM_SCENARIO_NODE1_PID_FILE=\.\.\/bin\/pids\/node-1\.pid[\s\S]*scenario:p0/);
  assert.match(guide, /cluster-ha[\s\S]*最后/);
  assert.match(guide, /node-1[\s\S]*停止[\s\S]*(重启|恢复)/);
});
