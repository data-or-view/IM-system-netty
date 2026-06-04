import { IMError } from "../types.js";
import type { HttpTransport } from "../transport/http.js";

export type HttpAPI = Pick<HttpTransport, "get" | "post">;

const missingHttpTransport: HttpAPI = {
  get: () => Promise.reject(new IMError(-1, "HTTP API requires httpUrl")),
  post: () => Promise.reject(new IMError(-1, "HTTP API requires httpUrl")),
};

export function requireHttp(transport?: HttpAPI): HttpAPI {
  return transport ?? missingHttpTransport;
}
