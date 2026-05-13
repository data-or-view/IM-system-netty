/**
 * IM 协议层 —— 二进制编码解码。
 *
 * 帧结构（与 Java 端 IMEncoder/IMDecoder 一致）：
 *   magic(2) + version(1) + flags(1) + bodyLen(4) + headerLen(2) + headerJson + body
 *
 * magic = 0xCA 0xFE
 * flags bit0: body 有无（0=无, 1=有）
 * headerJson: 普通 JSON 对象，字段为 String→String
 * body: 二进制 raw bytes
 */

export const MAGIC = 0xcafe;
export const VERSION = 1;

export interface IMHeader {
  _op?: string; // command code 或 ack code
  _seq?: string;
  _mid?: string;
  _ts?: string;
  _ver?: string;
  _flg?: string;
  status?: string;
  token?: string;
  reason?: string;
  userId?: string;
  fromUserId?: string;
  toUserId?: string;
  groupId?: string;
  contentType?: string;
  content?: string;
  keyword?: string;
  limit?: string;
  groupName?: string;
  faceUrl?: string;
  members?: string;
  groupType?: string;
  needVerification?: string;
  reqMsg?: string;
  targetUserId?: string;
  notification?: string;
  [key: string]: string | undefined;
}

export interface IMBinaryFrame {
  header: IMHeader;
  body?: Uint8Array;
}

/** 编码：IMHeader → Uint8Array */
export function encodeFrame(header: IMHeader, body?: Uint8Array): Uint8Array {
  const headerJson = JSON.stringify(header);
  const headerBytes = new TextEncoder().encode(headerJson);
  const bodyLen = body?.length ?? 0;
  const hasBody = body != null && body.length > 0;

  const buf = new ArrayBuffer(9 + headerBytes.length + bodyLen);
  const dv = new DataView(buf);

  // magic
  dv.setUint16(0, MAGIC);
  // version
  dv.setUint8(2, VERSION);
  // flags
  dv.setUint8(3, hasBody ? 1 : 0);
  // body length
  dv.setUint32(4, bodyLen);
  // header length
  dv.setUint16(8, headerBytes.length);
  // header JSON
  new Uint8Array(buf, 9, headerBytes.length).set(headerBytes);
  // body
  if (body && body.length > 0) {
    new Uint8Array(buf, 9 + headerBytes.length, body.length).set(body);
  }

  return new Uint8Array(buf);
}

/** 解析缓冲区中的帧，返回 [帧, 剩余字节] 或 null */
export function decodeFrame(buffer: Uint8Array): [IMBinaryFrame, Uint8Array] | null {
  if (buffer.length < 9) return null;

  const dv = new DataView(buffer.buffer, buffer.byteOffset, buffer.byteLength);
  const magic = dv.getUint16(0);
  if (magic !== MAGIC) {
    throw new Error(`Invalid magic: 0x${magic.toString(16)}`);
  }

  const bodyLen = dv.getUint32(4);
  const headerLen = dv.getUint16(8);
  const total = 9 + headerLen + bodyLen;

  if (buffer.length < total) return null;

  const headerJson = new TextDecoder().decode(buffer.subarray(9, 9 + headerLen));
  const header: IMHeader = JSON.parse(headerJson);
  const body = bodyLen > 0 ? buffer.subarray(9 + headerLen, total) : undefined;

  return [{ header, body }, buffer.subarray(total)];
}

/** 命令码枚举（与 Java CommandType 对齐） */
export const CMD = {
  HEARTBEAT: 0,
  HEARTBEAT_ACK: 1,
  LOGIN: 10,
  LOGIN_ACK: 11,
  SINGLE_CHAT: 20,
  SINGLE_CHAT_ACK: 21,
  GROUP_CHAT: 30,
  GROUP_CHAT_ACK: 31,
  PULL_MESSAGE: 50,
  PULL_MESSAGE_ACK: 51,
  CONVERSATION_GET: 60,
  CONVERSATION_GET_ACK: 61,
  CONVERSATION_SET: 62,
  CONVERSATION_SET_ACK: 63,
  FRIEND_APPLY: 70,
  FRIEND_APPLY_ACK: 71,
  FRIEND_APPROVE: 72,
  FRIEND_APPROVE_ACK: 73,
  FRIEND_REMOVE: 74,
  FRIEND_REMOVE_ACK: 75,
  FRIEND_LIST: 76,
  FRIEND_LIST_ACK: 77,
  GROUP_CREATE: 80,
  GROUP_CREATE_ACK: 81,
  GROUP_JOIN: 82,
  GROUP_JOIN_ACK: 83,
  GROUP_QUIT: 84,
  GROUP_QUIT_ACK: 85,
  GROUP_KICK: 86,
  GROUP_KICK_ACK: 87,
  GROUP_INFO_UPDATE: 88,
  GROUP_INFO_UPDATE_ACK: 89,
  USER_SEARCH: 92,
  USER_SEARCH_ACK: 93,
} as const;

export type CmdValue = (typeof CMD)[keyof typeof CMD];

/** 判断是否为 ACK */
export function isAck(op: number): boolean {
  return op % 2 === 1;
}

/** 反向查找命令名 */
export function cmdName(code: number): string {
  for (const [key, val] of Object.entries(CMD)) {
    if (val === code) return key;
  }
  return `UNKNOWN(${code})`;
}
