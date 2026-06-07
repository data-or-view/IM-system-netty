import { IMError } from "../types.js";

export type HttpAPI = {
  get<T>(path: string, query?: Record<string, unknown>): Promise<T>;
  post<T>(path: string, body?: Record<string, unknown>): Promise<T>;
};

const missingHttpTransport: HttpAPI = {
  get: () => Promise.reject(new IMError(-1, "HTTP API requires httpUrl")),
  post: () => Promise.reject(new IMError(-1, "HTTP API requires httpUrl")),
};

export function requireHttp(transport?: HttpAPI): HttpAPI {
  return transport ?? missingHttpTransport;
}
