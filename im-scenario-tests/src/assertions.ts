export function assertOk(condition: unknown, message: string): asserts condition {
  if (!condition) {
    throw new Error(message);
  }
}

export async function waitFor<T>(
  probe: () => T | undefined,
  options: { timeoutMs: number; intervalMs?: number; description: string; onTimeout?: () => string },
): Promise<T> {
  const intervalMs = options.intervalMs ?? 50;
  const deadline = Date.now() + options.timeoutMs;
  while (Date.now() <= deadline) {
    const value = probe();
    if (value !== undefined) return value;
    await sleep(intervalMs);
  }
  const detail = options.onTimeout?.();
  throw new Error(`Timed out waiting for ${options.description}${detail ? `; ${detail}` : ""}`);
}

export async function waitForAsync<T>(
  probe: () => Promise<T | undefined>,
  options: { timeoutMs: number; intervalMs?: number; description: string; onTimeout?: () => string },
): Promise<T> {
  const intervalMs = options.intervalMs ?? 50;
  const deadline = Date.now() + options.timeoutMs;
  while (Date.now() <= deadline) {
    const value = await probe();
    if (value !== undefined) return value;
    await sleep(intervalMs);
  }
  const detail = options.onTimeout?.();
  throw new Error(`Timed out waiting for ${options.description}${detail ? `; ${detail}` : ""}`);
}

export function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}
