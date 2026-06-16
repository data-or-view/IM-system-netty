import type { IMError } from "./errors.js";
import type { Message, OutgoingMessageContentTypeValue } from "./models/message.js";

// ── Connection Events ──

export * from "./errors.js";
export { OP, PUSH_OP } from "./protocol/ops.js";
export type { OpValue } from "./protocol/ops.js";
export * from "./models/message.js";

export type ConnectionState = "disconnected" | "connecting" | "connected" | "reconnecting";

// ── Protocol ──

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

export const PlatformID = {
  IOS: 1,
  ANDROID: 2,
  WINDOWS: 3,
  MACOS: 4,
  WEB: 5,
  LINUX: 7,
} as const;
export type PlatformIDValue = (typeof PlatformID)[keyof typeof PlatformID];

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

// ── System Message ──

export interface SystemChannel {
  channelId: string;
  channelName: string;
  channelType?: string;
  description?: string;
  status?: number;
  createdAt?: number;
  updatedAt?: number;
}

export interface SystemMessageSummary {
  messageId: string;
  channelId: string;
  channelName?: string;
  title: string;
  summary?: string;
  priority?: number;
  createdAt: number;
}

export interface SystemMessageInboxItem extends SystemMessageSummary {
  userId: string;
  content?: string;
  contentType?: string;
  readAt?: number;
  deleted?: boolean;
  archived?: boolean;
}

export interface SyncCursor {
  conversationId: string;
  lastSeq: number;
}

export interface ReconnectSyncResult {
  conversationId: string;
  messages: Message[];
  maxSeq: number;
}

export interface SystemUnreadCount {
  count: number;
  byChannel?: Record<string, number>;
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
  clientMsgId?: string;
}

export interface SendGroupMessageParam {
  groupId: string;
  contentType: OutgoingMessageContentTypeValue;
  content: unknown;
  clientMsgId?: string;
}

export interface SendMessageAck {
  messageId: string;
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
  clientMsgId?: string;
}

// ── 事件类型 ──

export interface IMEvents {
  /** 连接状态变更 */
  connectionStateChanged: (state: ConnectionState) => void;
  /** 收到新消息 */
  message: (msg: Message) => void;
  /** 收到一批新消息。Web 端优先订阅这个事件，避免高频推送触发渲染风暴。 */
  messageBatch: (msgs: Message[]) => void;
  /** 重连后补偿同步到的新消息。 */
  reconnectSync: (result: ReconnectSyncResult) => void;
  /** 收到好友申请 */
  friendRequest: (apply: FriendApply) => void;
  /** 收到加群申请或审批结果 */
  groupApply: (apply: GroupApply) => void;
  /** 收到系统通知。完整内容通过 system.detail(messageId) 拉取。 */
  systemMessage: (message: SystemMessageSummary) => void;
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
  /** ready()/waitConnected() 默认等待连接超时 ms（默认 10000）。 */
  connectTimeout?: number;
  /** 重连成功后是否按宿主提供的游标补消息（默认 false）。 */
  syncOnReconnect?: boolean;
  /** 返回需要在重连后补偿同步的会话游标。 */
  syncConversations?: () => SyncCursor[] | Promise<SyncCursor[]>;
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
