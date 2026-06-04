import { IMError, type FileUploadResult } from "../types.js";

type FetchLike = (input: string, init?: RequestInit) => Promise<Response>;

export interface HttpTransportOptions {
  baseUrl: string;
  getToken?: () => string | null;
  fetchImpl?: FetchLike;
}

interface HttpEnvelope<T> {
  code: number;
  data?: T;
  msg?: string;
  message?: string;
  detail?: string;
  imCode?: number;
}

export class HttpTransport {
  private readonly baseUrl: string;
  private readonly getToken: () => string | null;
  private readonly fetchImpl: FetchLike;

  constructor(opts: HttpTransportOptions) {
    this.baseUrl = opts.baseUrl.replace(/\/+$/, "");
    this.getToken = opts.getToken ?? (() => null);
    this.fetchImpl = opts.fetchImpl ?? ((input, init) => globalThis.fetch(input, init));
  }

  get<T>(path: string, query: Record<string, unknown> = {}): Promise<T> {
    return this.request<T>(`${path}${this.queryString(query)}`, {
      method: "GET",
      headers: this.authHeader(),
    });
  }

  post<T>(path: string, body: Record<string, unknown> = {}): Promise<T> {
    return this.postJson<T>(path, body);
  }

  uploadFile(fileName: string, fileData: Uint8Array, mimeType: string): Promise<FileUploadResult> {
    return this.postBinary("/api/file/upload", { fileName, mimeType }, fileData);
  }

  multipartInit(fileName: string, fileSize: number, mimeType: string): Promise<{ uploadId: string; fileId?: string; objectId?: string }> {
    return this.postJson("/api/file/multipart/init", { fileName, fileSize, mimeType });
  }

  uploadPart(uploadId: string, partNumber: number, data: Uint8Array): Promise<string> {
    return this.postBinary<{ etag?: string }>("/api/file/multipart/upload", { uploadId, partNumber }, data)
      .then((result) => result.etag ?? "");
  }

  multipartComplete(uploadId: string, parts: Array<{ partNumber: number; etag: string }>): Promise<FileUploadResult> {
    return this.postJson("/api/file/multipart/complete", { uploadId, parts });
  }

  multipartAbort(uploadId: string): Promise<void> {
    return this.postJson("/api/file/multipart/abort", { uploadId }).then(() => undefined);
  }

  private postBinary<T>(path: string, query: Record<string, string | number>, body: Uint8Array): Promise<T> {
    return this.request<T>(`${path}${this.queryString(query)}`, {
      method: "POST",
      headers: {
        ...this.authHeader(),
        "Content-Type": "application/octet-stream",
      },
      body: this.toArrayBuffer(body),
    });
  }

  private postJson<T>(path: string, body: Record<string, unknown>): Promise<T> {
    return this.request<T>(path, {
      method: "POST",
      headers: {
        ...this.authHeader(),
        "Content-Type": "application/json",
      },
      body: JSON.stringify(body),
    });
  }

  private async request<T>(path: string, init: RequestInit): Promise<T> {
    const resp = await this.fetchImpl(`${this.baseUrl}${path}`, init);
    let payload: unknown;
    try {
      payload = await resp.json();
    } catch (err) {
      throw new IMError(resp.status || -1, "Invalid HTTP response", err instanceof Error ? err.message : String(err));
    }

    if (this.isEnvelope(payload)) {
      if (!resp.ok || payload.code !== 0) {
        throw new IMError(payload.imCode || payload.code || resp.status || -1, payload.msg || payload.message || "HTTP request failed", payload.detail);
      }
      return payload.data as T;
    }

    if (!resp.ok) {
      const error = payload as Partial<HttpEnvelope<T>> | null;
      throw new IMError(error?.imCode || error?.code || resp.status || -1, error?.msg || error?.message || "HTTP request failed", error?.detail);
    }
    return payload as T;
  }

  private isEnvelope<T>(payload: unknown): payload is HttpEnvelope<T> {
    if (!payload || typeof payload !== "object") return false;
    const candidate = payload as Partial<HttpEnvelope<T>>;
    return typeof candidate.code === "number" && ("data" in candidate || "msg" in candidate || "message" in candidate || "imCode" in candidate);
  }

  private authHeader(): Record<string, string> {
    const token = this.getToken();
    return token ? { Authorization: token.startsWith("Bearer ") ? token : `Bearer ${token}` } : {};
  }

  private toArrayBuffer(bytes: Uint8Array): ArrayBuffer {
    const copy = new ArrayBuffer(bytes.byteLength);
    new Uint8Array(copy).set(bytes);
    return copy;
  }

  private queryString(query: Record<string, unknown>): string {
    const params = new URLSearchParams();
    for (const [key, value] of Object.entries(query)) {
      if (value !== undefined && value !== null) {
        params.set(key, String(value));
      }
    }
    const encoded = params.toString();
    return encoded ? `?${encoded}` : "";
  }
}
