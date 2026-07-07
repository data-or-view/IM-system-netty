import { MessageContentType, toMessageContentType } from "im-sdk";
import type {
  Message as SDKMessage,
  OutgoingMessageContentTypeValue,
  SendMessageAck,
} from "im-sdk";

export interface ViewMessage {
  messageId: string;
  seq: number;
  senderUserId: string;
  senderNickname?: string;
  conversationId: string;
  contentType: number;
  content: string;
  createTime: number;
  status: number;
  errorText?: string;
}

export const VIEW_MESSAGE_STATUS = {
  FAILED: -1,
  PENDING: 0,
  SENT: 1,
} as const;

export type ViewMessageStatus = (typeof VIEW_MESSAGE_STATUS)[keyof typeof VIEW_MESSAGE_STATUS];

export const LOCAL_PENDING_SEQ = 0;
export const REVOKED_MESSAGE_TEXT = "消息已撤回";

export function toViewMessage(sdkMsg: SDKMessage): ViewMessage {
  const seq = sdkMsg.messageSeq ?? sdkMsg.sequenceId ?? LOCAL_PENDING_SEQ;
  return {
    messageId: sdkMsg.messageId,
    seq,
    senderUserId: sdkMsg.fromUserId,
    senderNickname: undefined,
    conversationId: sdkMsg.conversationId,
    contentType: Number(sdkMsg.contentType),
    content: sdkMsg.content,
    createTime: sdkMsg.timestamp,
    status: normalizeMessageStatus(sdkMsg.status, seq),
  };
}

export function normalizeMessageStatus(status: unknown, seq = LOCAL_PENDING_SEQ): number {
  if (status === VIEW_MESSAGE_STATUS.FAILED || status === "FAILED") return VIEW_MESSAGE_STATUS.FAILED;
  if (status === VIEW_MESSAGE_STATUS.PENDING || status === "PENDING") {
    return seq > LOCAL_PENDING_SEQ ? VIEW_MESSAGE_STATUS.SENT : VIEW_MESSAGE_STATUS.PENDING;
  }
  return VIEW_MESSAGE_STATUS.SENT;
}

export function toOutgoingMessageContent(raw: unknown): string {
  return typeof raw === "string" ? raw : JSON.stringify(raw);
}

export function toOptimisticMessage(
  ack: SendMessageAck,
  currentUserId: string,
  contentType: OutgoingMessageContentTypeValue,
  content: unknown,
): ViewMessage {
  return {
    messageId: ack.messageId,
    seq: ack.seq ?? LOCAL_PENDING_SEQ,
    senderUserId: currentUserId,
    conversationId: ack.conversationId,
    contentType: toMessageContentType(contentType),
    content: toOutgoingMessageContent(content),
    createTime: Date.now(),
    status: VIEW_MESSAGE_STATUS.SENT,
  };
}

export function toLocalPendingMessage(input: {
  conversationId: string;
  senderUserId: string;
  contentType: OutgoingMessageContentTypeValue | number;
  content: unknown;
  messageId?: string;
  createTime?: number;
}): ViewMessage {
  return {
    messageId: input.messageId ?? `local_${Date.now()}_${Math.random().toString(36).slice(2)}`,
    seq: LOCAL_PENDING_SEQ,
    senderUserId: input.senderUserId,
    conversationId: input.conversationId,
    contentType: typeof input.contentType === "number" ? input.contentType : toMessageContentType(input.contentType),
    content: toOutgoingMessageContent(input.content),
    createTime: input.createTime ?? Date.now(),
    status: VIEW_MESSAGE_STATUS.PENDING,
  };
}

export function toLocalFailedMessage(message: ViewMessage, errorText: string): ViewMessage {
  return {
    ...message,
    status: VIEW_MESSAGE_STATUS.FAILED,
    errorText,
  };
}

export function messageRenderKey(msg: {
  messageId?: string;
  conversationId?: string;
  seq?: number;
  senderUserId?: string;
  createTime?: number;
  content?: string;
}): string {
  if (msg.conversationId && msg.seq && msg.seq > 0) return `seq:${msg.conversationId}:${msg.seq}`;
  if (msg.conversationId && msg.messageId) return `${msg.conversationId}:${msg.messageId}`;
  if (msg.messageId) return msg.messageId;
  if (msg.seq && msg.seq > 0) return `seq:${msg.seq}`;
  return `tmp:${msg.senderUserId || "unknown"}:${msg.createTime || 0}:${msg.content || ""}`;
}

export function toRevokedMessage(message: ViewMessage): ViewMessage {
  return {
    ...message,
    contentType: MessageContentType.REVOKED,
    content: REVOKED_MESSAGE_TEXT,
  };
}
