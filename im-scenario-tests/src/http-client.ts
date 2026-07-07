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
      const parsed = text ? JSON.parse(text) as { code?: number; msg?: string; data?: T } | T : undefined;
      if (!response.ok) {
        throw new Error(`HTTP ${response.status} ${url.pathname}: ${text}`);
      }
      if (parsed && typeof parsed === "object" && "code" in parsed) {
        const envelope = parsed as { code?: number; msg?: string; data?: T };
        if (envelope.code !== undefined && envelope.code !== SCENARIO_SUCCESS_CODE) {
          throw new Error(`API ${envelope.code}: ${envelope.msg ?? "unknown error"}`);
        }
        return envelope.data as T;
      }
      return parsed as T;
    } finally {
      clearTimeout(timeout);
    }
  }
}

function requestId(): string {
  return `scenario-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
}
