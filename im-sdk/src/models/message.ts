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
  sfuEndpoint?: string;
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
  sfuEndpoint?: string;
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
    sfuEndpoint: content.sfuEndpoint,
    sdp: content.sdp ?? content._sdp,
    ice: content.ice ?? content._ice,
    duration: content.duration,
  };
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
