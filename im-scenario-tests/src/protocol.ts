export const SCENARIO_SUCCESS_CODE = 0;

export const SCENARIO_OP = {
  LOGIN: "login",
  CHAT_SEND: "chat.send",
  CHAT_SEND_GROUP: "chat.send.group",
} as const;

export const SCENARIO_PUSH_OP = {
  MESSAGE: "message",
  FRIEND_APPLY: "friend.apply",
  GROUP_APPLY: "group.apply",
  MESSAGE_REVOKED: "msg_revoke",
} as const;

export const SCENARIO_CONTENT_TYPE = {
  TEXT: "text",
  SYSTEM: 4,
  SIGNAL: 5,
} as const;

export const GROUP_JOIN_VERIFICATION_CODE = {
  DIRECT: 0,
  NEED_APPROVAL: 1,
} as const;

export const SCENARIO_HTTP_HEADER = {
  AUTHORIZATION: "Authorization",
  CONTENT_TYPE: "content-type",
  REQUEST_ID: "X-Request-Id",
} as const;

export const SCENARIO_HTTP_CONTENT_TYPE = {
  JSON: "application/json",
} as const;

export const SCENARIO_WS_FIELD = {
  AUTHORIZATION: "Authorization",
  REQUEST_ID: "_requestId",
} as const;
