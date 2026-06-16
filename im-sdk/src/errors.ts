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
    return err.detail || err.message || fallback;
  }
  if (err instanceof Error && err.message) {
    return err.message;
  }
  if (typeof err === "object" && err !== null) {
    const maybe = err as { detail?: unknown; message?: unknown };
    if (typeof maybe.detail === "string" && maybe.detail.trim()) return maybe.detail;
    if (typeof maybe.message === "string" && maybe.message.trim()) return maybe.message;
  }
  return fallback;
}

export function toIMError(err: unknown, fallback = "Unknown SDK error"): IMError {
  if (err instanceof IMError) return err;
  if (err instanceof Error) return new IMError(-1, err.message || fallback, undefined, "server");
  return new IMError(-1, fallback, typeof err === "string" ? err : undefined, "server");
}
