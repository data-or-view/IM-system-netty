let sequence = 0;

export function nextClientMsgId(prefix = "scenario"): string {
  sequence = (sequence + 1) % 1_000_000;
  return [
    prefix,
    Date.now().toString(36),
    process.pid.toString(36),
    sequence.toString(36),
    Math.random().toString(36).slice(2, 10),
  ].join(":");
}
