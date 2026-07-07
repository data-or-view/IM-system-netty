import { SCENARIO_DEFAULTS } from "./defaults.js";

export interface ScenarioConfig {
  httpUrl: string;
  wsUrl: string;
  defaultPassword: string;
  requestTimeoutMs: number;
  pollIntervalMs: number;
}

type Env = Record<string, string | undefined>;

export function loadScenarioConfig(env: Env = process.env): ScenarioConfig {
  return {
    httpUrl: env.IM_SCENARIO_HTTP_URL ?? SCENARIO_DEFAULTS.httpUrl,
    wsUrl: env.IM_SCENARIO_WS_URL ?? SCENARIO_DEFAULTS.wsUrl,
    defaultPassword: env.IM_SCENARIO_PASSWORD ?? SCENARIO_DEFAULTS.defaultPassword,
    requestTimeoutMs: parsePositiveInt(env.IM_SCENARIO_TIMEOUT_MS, SCENARIO_DEFAULTS.requestTimeoutMs),
    pollIntervalMs: parsePositiveInt(env.IM_SCENARIO_POLL_INTERVAL_MS, SCENARIO_DEFAULTS.pollIntervalMs),
  };
}

function parsePositiveInt(value: string | undefined, fallback: number): number {
  if (!value) return fallback;
  const parsed = Number.parseInt(value, 10);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : fallback;
}
