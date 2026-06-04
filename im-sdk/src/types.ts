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

// ── User ──

export interface UserInfo {
  userId: string;
  nickname?: string;
  faceUrl?: string;
  appMangerLevel?: number;
}

// ── Friend ──

export interface FriendInfo {
  ownerUserId: string;
  friendUserId: string;
  nickname?: string;
  faceUrl?: string;
  remark?: string;
  addSource: number;
  isPinned: boolean;
  createTime: number;
}

export interface FriendApply {
  fromUserId: string;
  toUserId: string;
  handleResult: number;
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
  groupType?: number;
  needVerification?: number;
  createTime?: number;
}

export interface GroupMember {
  groupId: string;
  userId: string;
  nickname?: string;
  faceUrl?: string;
  roleLevel: number;
  joinTime: number;
}

// ── Conversation ──

export interface Conversation {
  conversationId: string;
  ownerUserId: string;
  conversationType: number; // 1=单聊, 2=群聊
  userId?: string;
  groupId?: string;
  groupName?: string;
  showName: string;
  faceUrl?: string;
  latestMsg?: string;
  latestMsgSendTime?: number;
  unreadCount: number;
  recvMsgOpt: number;
  isPinned: boolean;
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
  contentType: string;
  content: string;
}

export interface RevokeMessageParam {
  conversationId: string;
  messageSeq: number;
  groupId?: string;
}

// ── 事件类型 ──

export interface IMEvents {
  /** 连接状态变更 */
  connectionStateChanged: (state: ConnectionState) => void;
  /** 收到新消息 */
  message: (msg: Message) => void;
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
}

export interface TokenPair {
  token?: string;
  refreshToken?: string;
  expiresIn?: number;
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
