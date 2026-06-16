import { IMProtocolError, type SendMessageAck } from "../types.js";

export function parseSendMessageAck(value: unknown): SendMessageAck {
  if (!value || typeof value !== "object") {
    throw invalidSendAck("response is not an object");
  }
  const data = value as Partial<SendMessageAck>;
  if (typeof data.messageId !== "string" || data.messageId.length === 0) {
    throw invalidSendAck("messageId is required");
  }
  if (typeof data.status !== "string" || data.status.length === 0) {
    throw invalidSendAck("status is required");
  }
  if (typeof data.conversationId !== "string" || data.conversationId.length === 0) {
    throw invalidSendAck("conversationId is required");
  }
  if (typeof data.seq !== "number") {
    throw invalidSendAck("seq is required");
  }
  return {
    messageId: data.messageId,
    status: data.status,
    conversationId: data.conversationId,
    seq: data.seq,
  };
}

function invalidSendAck(detail: string): IMProtocolError {
  return new IMProtocolError(`Invalid send message ack: ${detail}`);
}
