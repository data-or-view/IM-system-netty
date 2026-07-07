export const SCENARIO_DEFAULTS = {
  httpUrl: "http://127.0.0.1:8084",
  wsUrl: "ws://127.0.0.1:8083/ws",
  defaultPassword: "123456",
  requestTimeoutMs: 5_000,
  pollIntervalMs: 200,
} as const;

export const CLUSTER_NODE_DEFAULTS = {
  node1: {
    httpUrl: "http://127.0.0.1:8088",
    wsUrl: "ws://127.0.0.1:8081/ws",
  },
  node2: {
    httpUrl: "http://127.0.0.1:8089",
    wsUrl: "ws://127.0.0.1:8084/ws",
  },
} as const;
