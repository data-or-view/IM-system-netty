export const PROTOCOL_SUCCESS_CODE = 0;
export const WS_HEARTBEAT_SEQ = 0;

export const ACK_OP = {
  HEARTBEAT: "heartbeat_ack",
} as const;

export const WS_FRAME_FIELD = {
  AUTHORIZATION: "Authorization",
  REFRESH_TOKEN: "refreshToken",
  REQUEST_ID: "_requestId",
  SEQ: "seq",
} as const;

export const HTTP_HEADER = {
  AUTHORIZATION: "Authorization",
  CONTENT_TYPE: "Content-Type",
  REQUEST_ID: "X-Request-Id",
  ETAG: "ETag",
  ETAG_LOWERCASE: "etag",
} as const;

export const HTTP_CONTENT_TYPE = {
  JSON: "application/json",
} as const;

export const AUTH_SCHEME = {
  BEARER: "Bearer ",
} as const;
