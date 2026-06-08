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
  status: string;
  conversationId: string;
  seq: number;
}

export interface MessagePush {
  messageId?: string;
  conversationId?: string;
  fromUserId?: string;
  groupId?: string;
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
