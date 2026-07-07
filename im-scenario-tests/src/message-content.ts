export interface MessageLike {
  content?: unknown;
  contentType?: unknown;
}

export function parseMessageContent(messageOrContent: MessageLike | unknown): unknown {
  const raw = isMessageLike(messageOrContent) ? messageOrContent.content : messageOrContent;
  if (typeof raw !== "string") return raw;
  if (!raw.trim()) return raw;
  try {
    return JSON.parse(raw) as unknown;
  } catch {
    return raw;
  }
}

export function isSystemContent(content: unknown, systemType: string): boolean {
  const parsed = parseMessageContent(content);
  return isRecord(parsed) && parsed.systemType === systemType;
}

export function isSignalingContent(
  content: unknown,
  expected?: string | { roomId?: string; action?: string | number; callType?: string },
): boolean {
  const parsed = parseMessageContent(content);
  if (!isRecord(parsed)) return false;
  const actualRoomId = stringValue(parsed.roomId) ?? stringValue(parsed._room);
  const options = typeof expected === "string" ? { roomId: expected } : expected ?? {};
  if (options.roomId && actualRoomId !== options.roomId) return false;
  if (options.callType && parsed.callType !== options.callType) return false;
  if (options.action !== undefined) {
    const actualAction = parsed.action ?? parsed._act;
    if (typeof options.action === "string") {
      return typeof actualAction === "string" && actualAction.toUpperCase() === options.action.toUpperCase();
    }
    return actualAction === options.action;
  }
  return parsed.action !== undefined || parsed._act !== undefined;
}

export function hasTextContent(content: unknown, expectedText: string): boolean {
  const parsed = parseMessageContent(content);
  if (typeof parsed === "string") return parsed.includes(expectedText);
  if (!isRecord(parsed)) return false;
  return stringValue(parsed.text) === expectedText;
}

export function stringValue(value: unknown): string | undefined {
  return typeof value === "string" ? value : undefined;
}

export function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function isMessageLike(value: unknown): value is MessageLike {
  return isRecord(value) && "content" in value;
}
