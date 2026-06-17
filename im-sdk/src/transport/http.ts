import { IMHttpError, IMProtocolError, IMServerError, IMTimeoutError, type FileUploadResult } from "../types.js";

type FetchLike = (input: string, init?: RequestInit) => Promise<Response>;

export interface HttpTransportOptions {
  baseUrl: string;
  getToken?: () => string | null;
  fetchImpl?: FetchLike;
  requestIdFactory?: () => string;
  requestTimeout?: number;
}

interface HttpEnvelope<T> {
  code: number;
  data?: T;
  msg?: string;
  detail?: string;
  requestId?: string;
}

export class HttpTransport {
  private readonly baseUrl: string;
  private readonly getToken: () => string | null;
  private readonly fetchImpl: FetchLike;
  private readonly requestIdFactory: () => string;
  private readonly requestTimeout: number;

  constructor(opts: HttpTransportOptions) {
    this.baseUrl = opts.baseUrl.replace(/\/+$/, "");
    this.getToken = opts.getToken ?? (() => null);
    this.fetchImpl = opts.fetchImpl ?? ((input, init) => globalThis.fetch(input, init));
    this.requestIdFactory = opts.requestIdFactory ?? defaultRequestId;
    this.requestTimeout = opts.requestTimeout ?? 30_000;
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

  uploadFile(fileName: string, fileData: UploadBody, mimeType: string): Promise<FileUploadResult> {
    return this.postJson<PresignedUploadResponse>("/api/file/upload/sign", {
      fileName,
      fileSize: this.bodySize(fileData),
      mimeType,
    }).then(async (signed) => {
      await this.putObject(signed.uploadUrl, fileData, signed.headers);
      return this.postJson<FileUploadResult>("/api/file/upload/complete", { fileId: signed.fileId });
    });
  }

  multipartInit(fileName: string, fileSize: number, mimeType: string): Promise<{ uploadId: string; fileId?: string; objectId?: string }> {
    return this.postJson("/api/file/multipart/init", { fileName, fileSize, mimeType });
  }

  uploadPart(uploadId: string, partNumber: number, data: UploadBody): Promise<string> {
    return this.postJson<PresignedPartResponse>("/api/file/multipart/part-sign", { uploadId, partNumber })
      .then(async (signed) => {
        const response = await this.putObject(signed.uploadUrl, data, signed.headers);
        return response.headers.get("ETag") ?? response.headers.get("etag") ?? "";
      });
  }

  multipartComplete(uploadId: string, parts: Array<{ partNumber: number; etag: string }>): Promise<FileUploadResult> {
    return this.postJson("/api/file/multipart/complete", { uploadId, parts });
  }

  multipartAbort(uploadId: string): Promise<void> {
    return this.postJson("/api/file/multipart/abort", { uploadId }).then(() => undefined);
  }

  downloadSign(fileId: string): Promise<FileUploadResult> {
    return this.postJson("/api/file/download/sign", { fileId });
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

  private async putObject(url: string, body: UploadBody, signedHeaders: Record<string, string> = {}): Promise<Response> {
    const response = await this.fetchWithTimeout(url, {
      method: "PUT",
      headers: signedHeaders,
      body: this.toRequestBody(body),
    });
    if (!response.ok) {
      throw new IMHttpError(response.status, `Object storage upload failed: HTTP ${response.status}`);
    }
    return response;
  }

  private async request<T>(path: string, init: RequestInit): Promise<T> {
    const headers = {
      ...(init.headers as Record<string, string> | undefined),
      "X-Request-Id": this.requestIdFactory(),
    };
    const resp = await this.fetchWithTimeout(`${this.baseUrl}${path}`, { ...init, headers });
    let payload: unknown;
    try {
      payload = await resp.json();
    } catch (err) {
      throw new IMHttpError(resp.status || -1, "Invalid HTTP response", err instanceof Error ? err.message : String(err));
    }

    if (!this.isEnvelope(payload)) {
      throw new IMProtocolError("Invalid HTTP envelope", "Expected { code, msg, data } response");
    }

    if (!resp.ok || payload.code !== 0) {
      throw this.envelopeError(resp, payload);
    }
    return payload.data as T;
  }

  private isEnvelope<T>(payload: unknown): payload is HttpEnvelope<T> {
    if (!payload || typeof payload !== "object") return false;
    const candidate = payload as Partial<HttpEnvelope<T>>;
    return typeof candidate.code === "number" && typeof candidate.msg === "string";
  }

  private envelopeError<T>(resp: Response, payload: HttpEnvelope<T>): IMHttpError | IMServerError {
    const code = payload.code || resp.status || -1;
    const message = payload.msg || "HTTP request failed";
    if (!resp.ok) {
      return new IMHttpError(code, message, payload.detail);
    }
    return new IMServerError(code, message, payload.detail);
  }

  private authHeader(): Record<string, string> {
    const token = this.getToken();
    return token ? { Authorization: token.startsWith("Bearer ") ? token : `Bearer ${token}` } : {};
  }

  private async fetchWithTimeout(input: string, init: RequestInit): Promise<Response> {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), this.requestTimeout);
    try {
      return await this.fetchImpl(input, { ...init, signal: controller.signal });
    } catch (err) {
      if (this.isAbortError(err)) {
        throw new IMTimeoutError(-1, "HTTP request timeout", input);
      }
      throw err;
    } finally {
      clearTimeout(timer);
    }
  }

  private isAbortError(err: unknown): boolean {
    return err instanceof DOMException && err.name === "AbortError";
  }

  private toArrayBuffer(bytes: Uint8Array): ArrayBuffer {
    const copy = new ArrayBuffer(bytes.byteLength);
    new Uint8Array(copy).set(bytes);
    return copy;
  }

  private toRequestBody(body: UploadBody): BodyInit {
    if (body instanceof Uint8Array) {
      return this.toArrayBuffer(body);
    }
    return body;
  }

  private bodySize(body: UploadBody): number {
    return body instanceof Uint8Array ? body.byteLength : body.size;
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

type UploadBody = Uint8Array | Blob;

interface PresignedUploadResponse {
  fileId: string;
  uploadUrl: string;
  headers?: Record<string, string>;
}

interface PresignedPartResponse {
  uploadUrl: string;
  headers?: Record<string, string>;
}

function defaultRequestId(): string {
  const random = Math.random().toString(36).slice(2, 10);
  return `req_${Date.now().toString(36)}_${random}`;
}
