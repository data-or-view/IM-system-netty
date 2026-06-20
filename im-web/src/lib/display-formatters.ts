import {
  ConversationType,
  SignalingAction,
  normalizeSignalingContent,
} from "im-sdk";

export function displayText(value?: string | null): string | undefined {
  if (!value) return undefined;
  const trimmed = value.trim();
  if (!trimmed || trimmed === "undefined" || trimmed === "null") return undefined;
  return trimmed;
}

export function shortId(value?: string | null, head = 8, tail = 4): string {
  const text = displayText(value);
  if (!text) return "";
  if (text.length <= head + tail + 3) return text;
  return `${text.slice(0, head)}...${text.slice(-tail)}`;
}

export function formatMessagePreview(content?: string, conversationType?: number | string): string {
  if (!content) return "";
  try {
    const parsed = JSON.parse(content) as Record<string, unknown>;
    if (typeof parsed.text === "string") return parsed.text;
    if (typeof parsed.fileName === "string") return `[文件] ${parsed.fileName}`;
    if (typeof parsed.systemType === "string") return formatSystemPreview(parsed);
    if (typeof parsed.action === "string" || typeof parsed.action === "number" || typeof parsed.roomId === "string") {
      return formatSignalPreview(parsed, conversationType === ConversationType.GROUP);
    }
    if (typeof parsed.description === "string") return parsed.description;
  } catch {
    return content;
  }
  return content;
}

export function formatSystemPreview(content: Record<string, unknown>): string {
  const message = typeof content.message === "string" ? content.message.trim() : "";
  if (content.systemType === "group_role_changed" && message) {
    const match = message.match(/^(.+?) changed (.+?) to (admin|member)$/i);
    if (match) return match[3].toLowerCase() === "admin" ? "群管理员已更新" : "群管理员已取消";
  }
  if (content.systemType === "group_info_updated") return "群资料已更新";
  if (content.systemType === "group_created") return "群聊已创建";
  if (message) return localizeSystemMessage(String(content.systemType || ""), message) || message;
  switch (content.systemType) {
    case "group_created":
      return "群聊已创建";
    case "group_member_joined":
      return "有成员加入群聊";
    case "group_member_left":
      return "有成员离开群聊";
    case "group_info_updated":
      return "群资料已更新";
    case "group_role_changed":
      return "群成员权限已变更";
    default:
      return "系统消息";
  }
}

export function formatSignalPreview(content: unknown, isGroup: boolean): string {
  const signal = normalizeSignalingContent(content as never);
  const title = isGroup ? "群视频会议" : signal?.callType === "video" ? "视频通话" : "语音通话";
  switch (signal?.action) {
    case SignalingAction.INVITE:
    case SignalingAction.CALLING:
      return `${title}已发起`;
    case SignalingAction.ACCEPT:
      return isGroup ? "有成员加入群视频" : `${title}已接听`;
    case SignalingAction.CANCEL:
      return isGroup ? "有成员离开群视频" : `${title}已取消`;
    case SignalingAction.HANGUP:
      return isGroup ? "群视频已结束" : `${title}已结束`;
    case SignalingAction.REJECT:
      return `${title}已拒绝`;
    case SignalingAction.TIMEOUT:
      return `${title}未接听`;
    default:
      return title;
  }
}

export function localizeSystemMessage(systemType?: string, message?: string): string | undefined {
  if (!message) return undefined;
  if (systemType === "group_role_changed") {
    const match = message.match(/^(.+?) changed (.+?) to (admin|member)$/i);
    if (match) {
      const operator = shortId(match[1], 10, 4);
      const target = shortId(match[2], 10, 4);
      const role = match[3].toLowerCase() === "admin" ? "管理员" : "普通成员";
      return `${operator} 将 ${target} 设为${role}`;
    }
  }
  if (systemType === "group_info_updated") {
    const match = message.match(/^(.+?) updated group information$/i);
    return match ? `${shortId(match[1], 10, 4)} 更新了群资料` : "群资料已更新";
  }
  if (systemType === "group_created") {
    const match = message.match(/^(.+?) created the group(?: and invited (\d+) members)?$/i);
    if (match) {
      const operator = shortId(match[1], 10, 4);
      return match[2] ? `${operator} 创建了群聊，并邀请 ${match[2]} 位成员` : `${operator} 创建了群聊`;
    }
  }
  if (systemType === "group_member_joined") {
    const joined = message.match(/^(.+?) joined the group$/i);
    if (joined) return `${shortId(joined[1], 10, 4)} 加入了群聊`;
    const approved = message.match(/^(.+?) approved (.+?) to join the group$/i);
    if (approved) return `${shortId(approved[1], 10, 4)} 同意 ${shortId(approved[2], 10, 4)} 加入群聊`;
  }
  if (systemType === "group_member_left") {
    const left = message.match(/^(.+?) left the group$/i);
    if (left) return `${shortId(left[1], 10, 4)} 离开了群聊`;
    const removed = message.match(/^(.+?) removed (.+?) from the group$/i);
    if (removed) return `${shortId(removed[1], 10, 4)} 将 ${shortId(removed[2], 10, 4)} 移出群聊`;
  }
  return message;
}

export function cleanSystemText(text?: string): string {
  const value = text?.trim();
  if (!value) return "";
  if (!looksLikeJson(value)) return value;
  try {
    const parsed = JSON.parse(value) as Record<string, unknown>;
    if (typeof parsed.message === "string") return parsed.message;
    if (typeof parsed.summary === "string") return parsed.summary;
    if (typeof parsed.title === "string") return parsed.title;
  } catch {
    return "系统通知内容暂无法直接展示";
  }
  return "系统通知内容暂无法直接展示";
}

function looksLikeJson(value: string): boolean {
  return (value.startsWith("{") && value.endsWith("}")) || (value.startsWith("[") && value.endsWith("]"));
}
