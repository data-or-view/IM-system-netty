import { toMessageContentType } from "im-sdk";
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

export function toViewMessage(sdkMsg: SDKMessage): ViewMessage {
  return {
    messageId: sdkMsg.messageId,
    seq: sdkMsg.messageSeq ?? sdkMsg.sequenceId ?? 0,
    senderUserId: sdkMsg.fromUserId,
    senderNickname: undefined,
    conversationId: sdkMsg.conversationId,
    contentType: Number(sdkMsg.contentType),
    content: sdkMsg.content,
    createTime: sdkMsg.timestamp,
    status: sdkMsg.status,
  };
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
    seq: ack.seq ?? 0,
    senderUserId: currentUserId,
    conversationId: ack.conversationId,
    contentType: toMessageContentType(contentType),
    content: toOutgoingMessageContent(content),
    createTime: Date.now(),
    status: 1,
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
    seq: 0,
    senderUserId: input.senderUserId,
    conversationId: input.conversationId,
    contentType: typeof input.contentType === "number" ? input.contentType : toMessageContentType(input.contentType),
    content: toOutgoingMessageContent(input.content),
    createTime: input.createTime ?? Date.now(),
    status: 0,
  };
}

export function toLocalFailedMessage(message: ViewMessage, errorText: string): ViewMessage {
  return {
    ...message,
    status: -1,
    errorText,
  };
}

export function messageRenderKey(msg: {
  messageId?: string;
  seq?: number;
  senderUserId?: string;
  createTime?: number;
  content?: string;
}): string {
  if (msg.messageId) return msg.messageId;
  if (msg.seq && msg.seq > 0) return `seq:${msg.seq}`;
  return `tmp:${msg.senderUserId || "unknown"}:${msg.createTime || 0}:${msg.content || ""}`;
}
