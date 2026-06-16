export function createClientMsgId(): string {
  return `c_${Date.now().toString(36)}_${Math.random().toString(36).slice(2, 10).padEnd(8, "0")}`;
}
