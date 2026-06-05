// ── Connection Events ──

export type ConnectionState = "disconnected" | "connecting" | "connected" | "reconnecting";

// ── Protocol ──

/** 业务操作名映射（对应后端 Operation 枚举的 opName） */
export const OP = {
  // User
  USER_REGISTER: "user.register",
  USER_INFO: "user.info",
  USER_SEARCH: "user.search",
  USER_UPDATE: "user.update",
  // Friend
  FRIEND_APPLY: "friend.apply",
  FRIEND_APPROVE: "friend.approve",
  FRIEND_REMOVE: "friend.remove",
  FRIEND_LIST: "friend.list",
  FRIEND_BLACK: "friend.black",
  FRIEND_UNBLACK: "friend.unblack",
  FRIEND_BLACKLIST: "friend.blacklist",
  FRIEND_APPLY_SENT: "friend.get_sent_apply_list",
  FRIEND_APPLY_DETAIL: "friend.get_apply_detail",
  FRIEND_APPLY_UNHANDLED_COUNT: "friend.get_unhandled_apply_count",
  // Group
  GROUP_CREATE: "group.create",
  GROUP_JOIN: "group.join",
  GROUP_QUIT: "group.quit",
  GROUP_KICK: "group.kick",
  GROUP_DISBAND: "group.disband",
  GROUP_INFO_UPDATE: "group.info.update",
  GROUP_INFO: "group.info",
  GROUP_LIST: "group.list",
  GROUP_SEARCH: "group.search",
  GROUP_MEMBERS: "group.members",
  GROUP_MUTE_ALL: "group.mute_all",
  GROUP_APPLY_LIST: "group.apply.list",
  GROUP_APPLY_UNHANDLED_COUNT: "group.apply.unhandled.count",
  GROUP_APPLY_APPROVE: "group.apply.approve",
  // Conversation
  CONVERSATION_LIST: "conversation.list",
  CONVERSATION_SET: "conversation.set",
  CONVERSATION_READ: "conversation.read",
  // Message
  CHAT_PULL: "chat.pull",
  CHAT_SEQ: "chat.seq",
  CHAT_SYNC: "chat.sync",
  CHAT_SEARCH: "chat.search",
  CHAT_SEND: "chat.send",
  CHAT_SEND_GROUP: "chat.send.group",
  CHAT_REVOKE: "msg_revoke",
  // File
  FILE_UPLOAD: "file.upload",
  FILE_MULTIPART_INIT: "file.multipart.init",
  FILE_MULTIPART_UPLOAD: "file.multipart.upload",
  FILE_MULTIPART_COMPLETE: "file.multipart.complete",
  FILE_MULTIPART_ABORT: "file.multipart.abort",
  // Auth
  LOGIN: "login",
  REGISTER: "register",
  HEARTBEAT: "heartbeat",
} as const;

export type OpValue = (typeof OP)[keyof typeof OP];

/** 后端推送的 op 类型 */
export const PUSH_OP = {
  MESSAGE: "message",
  FRIEND_APPLY: "friend.apply",
  MESSAGE_REVOKED: "msg_revoke",
} as const;

// ── Request / Response ──

/** SDK 内部：完整的 WS 请求帧 */
export interface WSRequest {
  op: string;
  seq: number;
  Authorization?: string;
  [key: string]: unknown;
}

/** SDK 内部：完整的 WS 响应帧 */
export interface WSResponse {
  op: string;
  seq: number;
  code: number;
  data?: unknown;
  msg?: string;
  detail?: string;
}

/** SDK 内部：服务端推送帧 */
export interface WSPush {
  op: string;
  data: unknown;
  code?: number;
  msg?: string;
}

export interface MessageRevoked {
  conversationId: string;
  seq: number;
  revokerId?: string;
}


export const ApplyHandleResult = {
  PENDING: "PENDING",
  AGREED: "AGREED",
  REJECTED: "REJECTED",
} as const;
export type ApplyHandleResultValue = (typeof ApplyHandleResult)[keyof typeof ApplyHandleResult];

export const ApplySource = {
  UNKNOWN: "UNKNOWN",
  SEARCH: "SEARCH",
  QR_CODE: "QR_CODE",
  GROUP: "GROUP",
  INVITE: "INVITE",
} as const;
export type ApplySourceValue = (typeof ApplySource)[keyof typeof ApplySource];

export const ConversationType = {
  SINGLE: "SINGLE",
  GROUP: "GROUP",
} as const;
export type ConversationTypeValue = (typeof ConversationType)[keyof typeof ConversationType];

export const MessageReceiveOption = {
  NORMAL: "NORMAL",
  NOT_NOTIFY: "NOT_NOTIFY",
  NOT_RECEIVE: "NOT_RECEIVE",
} as const;
export type MessageReceiveOptionValue = (typeof MessageReceiveOption)[keyof typeof MessageReceiveOption];

export const GroupType = {
  PRIVATE: "PRIVATE",
  PUBLIC: "PUBLIC",
} as const;
export type GroupTypeValue = (typeof GroupType)[keyof typeof GroupType];

export const GroupJoinVerification = {
  DIRECT: "DIRECT",
  NEED_APPROVAL: "NEED_APPROVAL",
  INVITE_ONLY: "INVITE_ONLY",
  FORBIDDEN: "FORBIDDEN",
} as const;
export type GroupJoinVerificationValue = (typeof GroupJoinVerification)[keyof typeof GroupJoinVerification];

export const GroupMemberRole = {
  REMOVED: "REMOVED",
  MEMBER: "MEMBER",
  ADMIN: "ADMIN",
  OWNER: "OWNER",
} as const;
export type GroupMemberRoleValue = (typeof GroupMemberRole)[keyof typeof GroupMemberRole];

export const UserAdminLevel = {
  NORMAL: "NORMAL",
  ADMIN: "ADMIN",
  SUPER_ADMIN: "SUPER_ADMIN",
} as const;
export type UserAdminLevelValue = (typeof UserAdminLevel)[keyof typeof UserAdminLevel];

export function groupMemberRoleRank(role?: GroupMemberRoleValue): number {
  switch (role) {
    case GroupMemberRole.OWNER:
      return 200;
    case GroupMemberRole.ADMIN:
      return 100;
    case GroupMemberRole.REMOVED:
      return -1;
    case GroupMemberRole.MEMBER:
    default:
      return 1;
  }
}

export function isGroupConversation(conversation: { conversationType: ConversationTypeValue }): boolean {
  return conversation.conversationType === ConversationType.GROUP;
}

// ── User ──

export interface UserInfo {
  userId: string;
  nickname?: string;
  faceUrl?: string;
  appMangerLevel?: UserAdminLevelValue;
}

// ── Friend ──

export interface FriendInfo {
  ownerUserId: string;
  friendUserId: string;
  nickname?: string;
  faceUrl?: string;
  remark?: string;
  addSource: ApplySourceValue;
  isPinned: boolean;
  createTime: number;
}

export interface FriendApply {
  fromUserId: string;
  toUserId: string;
  handleResult: ApplyHandleResultValue;
  reqMsg?: string;
  createTime: number;
}

// ── Group ──

export interface GroupInfo {
  groupId: string;
  groupName: string;
  faceUrl?: string;
  ownerUserId?: string;
  memberCount?: number;
  groupType?: GroupTypeValue;
  needVerification?: GroupJoinVerificationValue;
  createTime?: number;
}

export interface GroupMember {
  groupId: string;
  userId: string;
  nickname?: string;
  faceUrl?: string;
  roleLevel: GroupMemberRoleValue;
  joinTime: number;
}

export interface GroupApply {
  groupId: string;
  userId: string;
  reqMsg?: string;
  handledMsg?: string;
  handlerUserId?: string;
  handleResult: ApplyHandleResultValue;
  joinSource?: ApplySourceValue;
  inviterUserId?: string;
  createTime: number;
  handledTime?: number;
}

// ── Conversation ──

export interface Conversation {
  conversationId: string;
  ownerUserId: string;
  conversationType: ConversationTypeValue;
  userId?: string;
  groupId?: string;
  groupName?: string;
  showName: string;
  faceUrl?: string;
  latestMsg?: string;
  latestMsgSendTime?: number;
  unreadCount: number;
  recvMsgOpt: MessageReceiveOptionValue;
  isPinned: boolean;
}

export interface ConversationReadResult {
  conversationId: string;
  unreadCount: number;
}

// ── Message ──

export interface Message {
  messageId: string;
  sequenceId: number;
  timestamp: number;
  fromUserId: string;
  toUserId?: string;
  groupId?: string;
  conversationId: string;
  contentType: number;
  content: string;
  messageSeq: number;
  status: number;
}

export const MessageContentType = {
  TEXT: 1,
  FILE: 2,
  IMAGE: 3,
  SYSTEM: 4,
  SIGNAL: 5,
  VOICE: 6,
  VIDEO: 7,
  LOCATION: 8,
  AT_TEXT: 9,
  QUOTE: 10,
  CUSTOM: 11,
  REVOKED: 101,
} as const;

export type MessageContentTypeValue = (typeof MessageContentType)[keyof typeof MessageContentType];

export const OutgoingMessageContentType = {
  TEXT: "text",
  FILE: "file",
  IMAGE: "image",
  SYSTEM: "system",
  SIGNAL: "signal",
  VOICE: "voice",
  VIDEO: "video",
  LOCATION: "location",
  AT_TEXT: "at_text",
  QUOTE: "quote",
  CUSTOM: "custom",
} as const;

export type OutgoingMessageContentTypeValue =
  (typeof OutgoingMessageContentType)[keyof typeof OutgoingMessageContentType];

export function toMessageContentType(contentType: OutgoingMessageContentTypeValue): MessageContentTypeValue {
  switch (contentType) {
    case OutgoingMessageContentType.TEXT:
      return MessageContentType.TEXT;
    case OutgoingMessageContentType.FILE:
      return MessageContentType.FILE;
    case OutgoingMessageContentType.IMAGE:
      return MessageContentType.IMAGE;
    case OutgoingMessageContentType.SYSTEM:
      return MessageContentType.SYSTEM;
    case OutgoingMessageContentType.SIGNAL:
      return MessageContentType.SIGNAL;
    case OutgoingMessageContentType.VOICE:
      return MessageContentType.VOICE;
    case OutgoingMessageContentType.VIDEO:
      return MessageContentType.VIDEO;
    case OutgoingMessageContentType.LOCATION:
      return MessageContentType.LOCATION;
    case OutgoingMessageContentType.AT_TEXT:
      return MessageContentType.AT_TEXT;
    case OutgoingMessageContentType.QUOTE:
      return MessageContentType.QUOTE;
    case OutgoingMessageContentType.CUSTOM:
      return MessageContentType.CUSTOM;
  }
}

export interface TextContent {
  text: string;
}

export interface FileContent {
  uuid?: string;
  fileName: string;
  fileSize: number;
  url: string;
}

export interface PictureInfo {
  uuid?: string;
  type?: string;
  fileSize?: number;
  width?: number;
  height?: number;
  url: string;
}

export interface ImageContent {
  sourcePicture: PictureInfo;
  bigPicture?: PictureInfo;
  snapshotPicture?: PictureInfo;
}

export interface SystemContent {
  systemType?: string;
  message?: string;
}

export interface SignalingContent {
  action?: SignalingActionName | number | unknown;
  _act?: SignalingActionCode;
  callType?: "voice" | "video";
  roomId?: string;
  _room?: string;
  token?: string;
  _token?: string;
  sdp?: string;
  _sdp?: string;
  ice?: string;
  _ice?: string;
  duration?: number;
}

export const SignalingAction = {
  INVITE: "INVITE",
  CALLING: "CALLING",
  ACCEPT: "ACCEPT",
  REJECT: "REJECT",
  CANCEL: "CANCEL",
  HANGUP: "HANGUP",
  ICE: "ICE",
  TIMEOUT: "TIMEOUT",
} as const;

export type SignalingActionName = (typeof SignalingAction)[keyof typeof SignalingAction];
export type SignalingActionCode = 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8;

export const SignalingActionCodeMap: Record<SignalingActionName, SignalingActionCode> = {
  INVITE: 1,
  CALLING: 2,
  ACCEPT: 3,
  REJECT: 4,
  CANCEL: 5,
  HANGUP: 6,
  ICE: 7,
  TIMEOUT: 8,
};

const SIGNALING_ACTION_BY_CODE: Record<number, SignalingActionName> = {
  1: SignalingAction.INVITE,
  2: SignalingAction.CALLING,
  3: SignalingAction.ACCEPT,
  4: SignalingAction.REJECT,
  5: SignalingAction.CANCEL,
  6: SignalingAction.HANGUP,
  7: SignalingAction.ICE,
  8: SignalingAction.TIMEOUT,
};

export interface NormalizedSignalingContent {
  action: SignalingActionName;
  callType?: "voice" | "video";
  roomId?: string;
  token?: string;
  sdp?: string;
  ice?: string;
  duration?: number;
}

export interface StartCallAck {
  status: "CALLING";
  roomId: string;
  token: string;
  sfuEndpoint: string;
}

export interface VoiceContent {
  uuid?: string;
  url: string;
  fileSize?: number;
  duration: number;
}

export interface VideoContent {
  videoUrl: string;
  videoUuid?: string;
  videoType?: string;
  videoSize?: number;
  duration?: number;
  snapshotUrl?: string;
  snapshotWidth?: number;
  snapshotHeight?: number;
  snapshotSize?: number;
}

export interface LocationContent {
  description?: string;
  longitude: number;
  latitude: number;
}

export interface AtTextContent {
  text: string;
  atUserList: string[];
}

export interface QuoteContent {
  text: string;
  quotedMessageId: string;
  quotedSenderId?: string;
  quotedContent?: string;
}

export interface CustomContent {
  data: string;
  description?: string;
  extension?: string;
}

export type ParsedMessageContent =
  | { type: typeof MessageContentType.TEXT; content: TextContent; raw: string }
  | { type: typeof MessageContentType.FILE; content: FileContent; raw: string }
  | { type: typeof MessageContentType.IMAGE; content: ImageContent; raw: string }
  | { type: typeof MessageContentType.SYSTEM; content: SystemContent; raw: string }
  | { type: typeof MessageContentType.SIGNAL; content: SignalingContent; raw: string }
  | { type: typeof MessageContentType.VOICE; content: VoiceContent; raw: string }
  | { type: typeof MessageContentType.VIDEO; content: VideoContent; raw: string }
  | { type: typeof MessageContentType.LOCATION; content: LocationContent; raw: string }
  | { type: typeof MessageContentType.AT_TEXT; content: AtTextContent; raw: string }
  | { type: typeof MessageContentType.QUOTE; content: QuoteContent; raw: string }
  | { type: typeof MessageContentType.CUSTOM; content: CustomContent; raw: string }
  | { type: typeof MessageContentType.REVOKED; content: TextContent; raw: string }
  | { type: "unknown"; content: unknown; raw: string; contentType?: number };

export function parseMessageContent(message: Pick<Message, "contentType" | "content">): ParsedMessageContent {
  const raw = message.content ?? "";
  const parsed = parseContentPayload(raw);
  switch (message.contentType) {
    case MessageContentType.TEXT:
      return { type: MessageContentType.TEXT, content: toTextContent(parsed, raw), raw };
    case MessageContentType.FILE:
      if (isObject(parsed)) return { type: MessageContentType.FILE, content: parsed as unknown as FileContent, raw };
      break;
    case MessageContentType.IMAGE:
      if (isObject(parsed)) return { type: MessageContentType.IMAGE, content: parsed as unknown as ImageContent, raw };
      break;
    case MessageContentType.SYSTEM:
      return {
        type: MessageContentType.SYSTEM,
        content: isObject(parsed) ? parsed as unknown as SystemContent : { message: raw },
        raw,
      };
    case MessageContentType.SIGNAL:
      if (isObject(parsed)) return { type: MessageContentType.SIGNAL, content: parsed as unknown as SignalingContent, raw };
      break;
    case MessageContentType.VOICE:
      if (isObject(parsed)) return { type: MessageContentType.VOICE, content: parsed as unknown as VoiceContent, raw };
      break;
    case MessageContentType.VIDEO:
      if (isObject(parsed)) return { type: MessageContentType.VIDEO, content: parsed as unknown as VideoContent, raw };
      break;
    case MessageContentType.LOCATION:
      if (isObject(parsed)) return { type: MessageContentType.LOCATION, content: parsed as unknown as LocationContent, raw };
      break;
    case MessageContentType.AT_TEXT:
      if (isObject(parsed)) return { type: MessageContentType.AT_TEXT, content: parsed as unknown as AtTextContent, raw };
      break;
    case MessageContentType.QUOTE:
      if (isObject(parsed)) return { type: MessageContentType.QUOTE, content: parsed as unknown as QuoteContent, raw };
      break;
    case MessageContentType.CUSTOM:
      if (isObject(parsed)) return { type: MessageContentType.CUSTOM, content: parsed as unknown as CustomContent, raw };
      break;
    case MessageContentType.REVOKED:
      return { type: MessageContentType.REVOKED, content: { text: raw || "消息已撤回" }, raw };
  }
  return { type: "unknown", content: parsed, raw, contentType: message.contentType };
}

function parseContentPayload(raw: string): unknown {
  if (!raw) return "";
  try {
    return JSON.parse(raw);
  } catch {
    return raw;
  }
}

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function toTextContent(value: unknown, raw: string): TextContent {
  if (isObject(value) && typeof value.text === "string") {
    return { text: value.text };
  }
  return { text: raw };
}

export interface SearchMessagesParam {
  conversationId: string;
  keyword: string;
  contentTypeFilter?: string[];
  startTime?: number;
  endTime?: number;
  pageSize?: number;
  page?: number;
}

export interface SearchMessagesResult {
  total: number;
  messages: Message[];
  hasMore: boolean;
}

export interface SendMessageParam {
  toUserId: string;
  contentType: OutgoingMessageContentTypeValue;
  content: unknown;
}

export interface SendMessageAck {
  status: string;
  conversationId: string;
  seq: number;
}

export interface RevokeMessageParam {
  conversationId: string;
  messageSeq: number;
  groupId?: string;
}

export interface StartCallParam {
  toUserId: string;
  callType: "voice" | "video";
}

export function normalizeSignalingContent(content: SignalingContent): NormalizedSignalingContent | null {
  const rawAction = content.action ?? content._act;
  let action: SignalingActionName | undefined;
  if (typeof rawAction === "string") {
    action = rawAction.toUpperCase() as SignalingActionName;
  } else if (typeof rawAction === "number") {
    action = SIGNALING_ACTION_BY_CODE[rawAction];
  }
  if (!action || !(action in SignalingActionCodeMap)) {
    return null;
  }
  return {
    action,
    callType: content.callType === "video" ? "video" : content.callType === "voice" ? "voice" : undefined,
    roomId: content.roomId ?? content._room,
    token: content.token ?? content._token,
    sdp: content.sdp ?? content._sdp,
    ice: content.ice ?? content._ice,
    duration: content.duration,
  };
}

// ── 事件类型 ──

export interface IMEvents {
  /** 连接状态变更 */
  connectionStateChanged: (state: ConnectionState) => void;
  /** 收到新消息 */
  message: (msg: Message) => void;
  /** 收到一批新消息。Web 端优先订阅这个事件，避免高频推送触发渲染风暴。 */
  messageBatch: (msgs: Message[]) => void;
  /** 收到好友申请 */
  friendRequest: (apply: FriendApply) => void;
  /** 收到消息撤回通知 */
  messageRevoked: (event: MessageRevoked) => void;
  /** 所有服务端推送的兜底事件 */
  push: (event: WSPush) => void;
  /** 登录或心跳续期后 token 发生变化 */
  tokenChanged: (tokens: TokenPair) => void;
  /** 错误 */
  error: (err: IMError) => void;
}

export type IMListener<K extends keyof IMEvents> = IMEvents[K];

// ── SDK Options ──

export interface IMOptions {
  wsUrl: string;
  httpUrl?: string;
  getToken?: () => string | null;
  getRefreshToken?: () => string | null;
  onTokenChanged?: (tokens: TokenPair) => void;
  /** 自动重连次数上限（默认 10） */
  maxReconnect?: number;
  /** 心跳间隔 ms（默认 7000） */
  heartbeatInterval?: number;
  /** 请求超时 ms（默认 30000） */
  requestTimeout?: number;
  /** 生成请求关联 ID。默认自动生成 req_xxx，用于前后端日志串联。 */
  requestIdFactory?: () => string;
  /** 消息推送批处理窗口 ms（默认 16，一帧内合并）。设为 0 可关闭批处理延迟。 */
  messageBatchInterval?: number;
  /** 单批消息数量上限（默认 100）。超过后立即刷出，避免缓冲过大。 */
  messageBatchSize?: number;
}

export interface TokenPair {
  token?: string;
  refreshToken?: string;
  expiresIn?: number;
}

export interface RegisterResult {
  userId: string;
  nickname: string;
  faceUrl?: string;
  status?: string;
}

export interface FileUploadResult {
  fileUrl: string;
  fileId: string;
  fileName: string;
  mimeType: string;
  fileSize?: string | number;
}

// ── Error ──

export class IMError extends Error {
  constructor(
    public code: number,
    message: string,
    public detail?: string,
  ) {
    super(message);
    this.name = "IMError";
  }
}

export class IMTimeoutError extends IMError {
  constructor(public override code: number = -1) {
    super(code, "Request timeout");
    this.name = "IMTimeoutError";
  }
}
