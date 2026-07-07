export const APP_ERROR_EVENT = "im:app-error";

export type AppErrorSeverity = "info" | "warning" | "error";

export interface AppErrorNotice {
  message: string;
  severity: AppErrorSeverity;
  source?: string;
  error?: unknown;
}

interface ErrorShape {
  code?: unknown;
  status?: unknown;
  kind?: unknown;
  name?: unknown;
  message?: unknown;
  detail?: unknown;
}

export function isAuthExpiredError(error: unknown): boolean {
  const shape = asErrorShape(error);
  const code = numericCode(shape);
  const kind = stringValue(shape.kind).toLowerCase();
  const text = errorText(shape).toLowerCase();

  if (kind === "auth") return true;
  if (code === 401) return true;
  return text.includes("missing token") || text.includes("invalid token") || text.includes("unauthorized");
}

export function authCheckFailureMessage(error: unknown): string {
  if (isAuthExpiredError(error)) {
    return "登录状态已失效，请重新登录";
  }

  const shape = asErrorShape(error);
  const code = numericCode(shape);
  const kind = stringValue(shape.kind).toLowerCase();
  if (kind === "timeout") {
    return "服务响应超时，请稍后重试";
  }
  if (kind === "connection") {
    return "暂时无法连接到后端，已保留登录状态";
  }
  if (code >= 500) {
    return "后端服务暂时不可用，已保留登录状态";
  }
  return "登录状态校验失败，已保留登录状态";
}

export function toAppErrorNotice(error: unknown, fallback = "操作失败", source?: string): AppErrorNotice {
  const shape = asErrorShape(error);
  const kind = stringValue(shape.kind).toLowerCase();
  const code = numericCode(shape);
  const message = stringValue(shape.detail) || stringValue(shape.message) || fallback;

  const severity: AppErrorSeverity =
    kind === "connection" || kind === "timeout" || code >= 500 ? "warning" : "error";

  return { message, severity, source, error };
}

export function notifyAppError(error: unknown, fallback = "操作失败", source?: string): void {
  if (typeof window === "undefined") return;
  window.dispatchEvent(new CustomEvent(APP_ERROR_EVENT, {
    detail: toAppErrorNotice(error, fallback, source),
  }));
}

function asErrorShape(error: unknown): ErrorShape {
  return error && typeof error === "object" ? error as ErrorShape : {};
}

function numericCode(shape: ErrorShape): number {
  const raw = typeof shape.code === "number" ? shape.code : shape.status;
  return typeof raw === "number" ? raw : -1;
}

function errorText(shape: ErrorShape): string {
  return `${stringValue(shape.detail)} ${stringValue(shape.message)} ${stringValue(shape.name)}`;
}

function stringValue(value: unknown): string {
  return typeof value === "string" ? value.trim() : "";
}
