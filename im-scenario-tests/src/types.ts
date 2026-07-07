export interface RegisterResult {
  userId: string;
  nickname?: string;
  faceUrl?: string;
  status?: string;
}

export interface TokenPair {
  token?: string;
  refreshToken?: string;
  expiresIn?: number;
}

export interface GroupInfo {
  groupId: string;
  groupName: string;
  memberCount?: number;
}

export interface SendMessageAck {
  messageId?: string;
  status: string;
  conversationId: string;
  seq: number;
}

export interface ScenarioMessage {
  messageId?: string;
  conversationId?: string;
  fromUserId?: string;
  toUserId?: string;
  groupId?: string;
  contentType?: number;
  content?: unknown;
  messageSeq?: number;
  seq?: number;
}

export interface MessagePush {
  messageId?: string;
  conversationId?: string;
  fromUserId?: string;
  groupId?: string;
  contentType?: number;
  content?: unknown;
  messageSeq?: number;
  seq?: number;
}

export interface GroupCallSession {
  active: boolean;
  ended?: boolean;
  groupId?: string;
  roomId?: string;
  callType?: "voice" | "video";
  initiatorUserId?: string;
  sfuEndpoint?: string;
  startedAt?: number;
  participantCount?: number;
}

export interface GroupCallJoinResult extends GroupCallSession {
  token: string;
  sfuEndpoint: string;
  roomId: string;
}

export interface SystemMessageInboxItem {
  messageId: string;
  channelId: string;
  channelName?: string;
  title: string;
  summary?: string;
  content?: string;
  contentType?: string;
  readAt?: number;
  createdAt?: number;
}

export interface ConversationInfo {
  conversationId: string;
  conversationType?: string | number;
  userId?: string;
  groupId?: string;
  latestMsg?: string;
  latestMsgSendTime?: number;
  unreadCount?: number;
}

export interface FriendApplyInfo {
  fromUserId: string;
  toUserId: string;
  reqMsg?: string;
  handleResult: "PENDING" | "AGREED" | "REJECTED";
}

export interface FriendInfo {
  ownerUserId?: string;
  friendUserId: string;
  nickname?: string;
}

export interface GroupApplyInfo {
  groupId: string;
  userId: string;
  reqMsg?: string;
  handleResult: "PENDING" | "AGREED" | "REJECTED";
  handlerUserId?: string;
}

export interface GroupMemberInfo {
  groupId: string;
  userId: string;
  roleLevel?: string;
}
