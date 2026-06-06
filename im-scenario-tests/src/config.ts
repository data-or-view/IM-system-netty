export interface ScenarioConfig {
  httpUrl: string;
  wsUrl: string;
  defaultPassword: string;
  requestTimeoutMs: number;
}

type Env = Record<string, string | undefined>;

export function loadScenarioConfig(env: Env = process.env): ScenarioConfig {
  return {
    httpUrl: env.IM_SCENARIO_HTTP_URL ?? "http://127.0.0.1:8084",
    wsUrl: env.IM_SCENARIO_WS_URL ?? "ws://127.0.0.1:8083/ws",
    defaultPassword: env.IM_SCENARIO_PASSWORD ?? "123456",
    requestTimeoutMs: parsePositiveInt(env.IM_SCENARIO_TIMEOUT_MS, 5_000),
  };
}

function parsePositiveInt(value: string | undefined, fallback: number): number {
  if (!value) return fallback;
  const parsed = Number.parseInt(value, 10);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : fallback;
}
