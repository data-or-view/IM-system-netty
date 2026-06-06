export function assertOk(condition: unknown, message: string): asserts condition {
  if (!condition) {
    throw new Error(message);
  }
}

export async function waitFor<T>(
  probe: () => T | undefined,
  options: { timeoutMs: number; intervalMs?: number; description: string },
): Promise<T> {
  const intervalMs = options.intervalMs ?? 50;
  const deadline = Date.now() + options.timeoutMs;
  while (Date.now() <= deadline) {
    const value = probe();
    if (value !== undefined) return value;
    await sleep(intervalMs);
  }
  throw new Error(`Timed out waiting for ${options.description}`);
}

export function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}
