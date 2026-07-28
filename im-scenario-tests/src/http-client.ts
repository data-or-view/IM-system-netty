import {
  SCENARIO_HTTP_CONTENT_TYPE,
  SCENARIO_HTTP_HEADER,
  SCENARIO_SUCCESS_CODE,
} from "./protocol.js";

export interface HttpClientOptions {
  baseUrl: string;
  getToken?: () => string | undefined;
  requestTimeoutMs: number;
}

interface ApiEnvelope<T = unknown> {
  code?: number;
  msg?: string;
  data?: T;
  detail?: string;
  requestId?: string;
}

export class ScenarioHttpError extends Error {
  readonly name = "ScenarioHttpError";

  constructor(
    readonly path: string,
    readonly httpStatus: number,
    readonly code: number | undefined,
    readonly msg: string | undefined,
    readonly detail: string | undefined,
    readonly requestId: string | undefined,
    readonly responseBody: string,
  ) {
    const status = `HTTP ${httpStatus}`;
    const business = code !== undefined ? ` API ${code}` : "";
    const description = detail ?? msg ?? (responseBody || "unknown error");
    super(`${status}${business} ${path}: ${description}`);
  }
}

export class ScenarioHttpClient {
  constructor(private readonly options: HttpClientOptions) {}

  get<T>(path: string, query?: Record<string, unknown>): Promise<T> {
    const url = new URL(path, this.options.baseUrl);
    for (const [key, value] of Object.entries(query ?? {})) {
      if (value !== undefined && value !== null) url.searchParams.set(key, String(value));
    }
    return this.request<T>(url, { method: "GET" });
  }

  post<T>(path: string, body?: Record<string, unknown>): Promise<T> {
    const url = new URL(path, this.options.baseUrl);
    return this.request<T>(url, {
      method: "POST",
      headers: { [SCENARIO_HTTP_HEADER.CONTENT_TYPE]: SCENARIO_HTTP_CONTENT_TYPE.JSON },
      body: JSON.stringify(body ?? {}),
    });
  }

  private async request<T>(url: URL, init: RequestInit): Promise<T> {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), this.options.requestTimeoutMs);
    try {
      const headers = new Headers(init.headers);
      const token = this.options.getToken?.();
      if (token) headers.set(SCENARIO_HTTP_HEADER.AUTHORIZATION, token);
      headers.set(SCENARIO_HTTP_HEADER.REQUEST_ID, requestId());

      const response = await fetch(url, { ...init, headers, signal: controller.signal });
      const text = await response.text();
      const parsed = parseResponseBody<T>(text);
      const envelope = isApiEnvelope<T>(parsed) ? parsed : undefined;
      if (!response.ok) {
        throw new ScenarioHttpError(
          url.pathname,
          response.status,
          envelope?.code,
          envelope?.msg,
          envelope?.detail,
          envelope?.requestId,
          text,
        );
      }
      if (envelope) {
        if (envelope.code !== undefined && envelope.code !== SCENARIO_SUCCESS_CODE) {
          throw new ScenarioHttpError(
            url.pathname,
            response.status,
            envelope.code,
            envelope.msg,
            envelope.detail,
            envelope.requestId,
            text,
          );
        }
        return envelope.data as T;
      }
      return parsed as T;
    } finally {
      clearTimeout(timeout);
    }
  }
}

function parseResponseBody<T>(text: string): ApiEnvelope<T> | T | undefined {
  if (!text) return undefined;
  try {
    return JSON.parse(text) as ApiEnvelope<T> | T;
  } catch {
    return text as T;
  }
}

function isApiEnvelope<T>(value: ApiEnvelope<T> | T | undefined): value is ApiEnvelope<T> {
  return typeof value === "object" && value !== null && "code" in value;
}

function requestId(): string {
  return `scenario-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
}
