export type IMErrorKind =
  | "connection"
  | "timeout"
  | "protocol"
  | "auth"
  | "http"
  | "server"
  | "config";

export class IMError extends Error {
  constructor(
    public code: number,
    message: string,
    public detail?: string,
    public kind: IMErrorKind = "server",
  ) {
    super(message);
    this.name = "IMError";
  }
}

export class IMConnectionError extends IMError {
  constructor(message = "Not connected", detail?: string, code = -1) {
    super(code, message, detail, "connection");
    this.name = "IMConnectionError";
  }
}

export class IMTimeoutError extends IMError {
  constructor(public override code: number = -1, message = "Request timeout", detail?: string) {
    super(code, message, detail, "timeout");
    this.name = "IMTimeoutError";
  }
}

export class IMProtocolError extends IMError {
  constructor(message: string, detail?: string, code = -1) {
    super(code, message, detail, "protocol");
    this.name = "IMProtocolError";
  }
}

export class IMAuthError extends IMError {
  constructor(code: number, message = "Authentication failed", detail?: string) {
    super(code, message, detail, "auth");
    this.name = "IMAuthError";
  }
}

export class IMHttpError extends IMError {
  constructor(code: number, message: string, detail?: string) {
    super(code, message, detail, "http");
    this.name = "IMHttpError";
  }
}

export class IMServerError extends IMError {
  constructor(code: number, message = "Server error", detail?: string) {
    super(code, message, detail, "server");
    this.name = "IMServerError";
  }
}

export class IMConfigError extends IMError {
  constructor(message: string, detail?: string, code = -1) {
    super(code, message, detail, "config");
    this.name = "IMConfigError";
  }
}

export function getErrorText(err: unknown, fallback = "未知错误"): string {
  if (err instanceof IMError) {
    return localizeErrorMessage(err.detail || err.message, err.code, fallback);
  }
  if (err instanceof Error && err.message) {
    return localizeErrorMessage(err.message, undefined, fallback);
  }
  if (typeof err === "object" && err !== null) {
    const maybe = err as { detail?: unknown; message?: unknown };
    if (typeof maybe.detail === "string" && maybe.detail.trim()) return localizeErrorMessage(maybe.detail, undefined, fallback);
    if (typeof maybe.message === "string" && maybe.message.trim()) return localizeErrorMessage(maybe.message, undefined, fallback);
  }
  return fallback;
}

function localizeErrorMessage(message: string | undefined, code: number | undefined, fallback: string): string {
  const text = message?.trim();
  if (!text) return fallback;
  const lower = text.toLowerCase();
  if (code === 401 || lower.includes("missing token") || lower.includes("invalid token") || lower.includes("unauthorized")) {
    return "登录状态已失效，请重新登录";
  }
  if (code === 403 || lower.includes("forbidden")) {
    if (lower.includes("conversation not readable")) return "你暂无权限查看该会话，请刷新好友或群聊状态后重试";
    if (lower.includes("not a group member")) return "你已不在该群聊中，无法继续操作";
    if (lower.includes("file does not belong")) return "你没有权限下载该文件";
    if (lower.includes("blocked by target user")) return "对方已将你拉黑，无法继续操作";
    if (lower.includes("groupid does not match")) return "会话和群聊不匹配，无法完成操作";
    if (lower.includes("admin permission required")) return "需要管理员权限";
    return "你没有权限执行此操作";
  }
  if (code === 404 || lower.includes("not found")) {
    return "资源不存在或已被删除";
  }
  if (lower.includes("对方已删除你")) return text;
  return text;
}

export function toIMError(err: unknown, fallback = "Unknown SDK error"): IMError {
  if (err instanceof IMError) return err;
  if (err instanceof Error) return new IMError(-1, err.message || fallback, undefined, "server");
  return new IMError(-1, fallback, typeof err === "string" ? err : undefined, "server");
}
