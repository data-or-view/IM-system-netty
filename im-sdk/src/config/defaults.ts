export const SDK_DEFAULTS = {
  maxReconnect: 10,
  heartbeatIntervalMs: 7_000,
  requestTimeoutMs: 30_000,
  connectTimeoutMs: 10_000,
  messageBatchIntervalMs: 16,
  messageBatchSize: 100,
  maxSeenMessageKeys: 1_000,
  reconnectBackoffBaseMs: 1_000,
  reconnectBackoffMaxMs: 30_000,
} as const;
