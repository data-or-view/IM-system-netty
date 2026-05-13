/**
 * WebSocket 连接管理器。
 *
 * 管理到 IM Server 的 WebSocket 连接：
 *   ① 自动心跳（7 秒间隔）
 *   ② 二进制帧编解码
 *   ③ 事件通知
 */

import { type IMHeader, type IMBinaryFrame, encodeFrame, decodeFrame, CMD } from "./protocol";
import { nextTraceId, logDebug, logInfo, logWarn, logError } from "@/utils/logger";

export type WsEventType = "open" | "close" | "error" | "message";
export type WsEventListener = (frame: IMBinaryFrame | null) => void;

export class IMConnection {
  private ws: WebSocket | null = null;
  private url = "";
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  private heartbeatTimer: ReturnType<typeof setInterval> | null = null;
  private reconnectAttempts = 0;
  private maxReconnect = 10;
  private buffer = new Uint8Array(0);
  private listeners = new Map<string, Set<WsEventListener>>();
  private seqCounter = 0;

  /** 连接状态 */
  connected = false;

  connect(host = "localhost", port = 8081, path = "/ws") {
    this.url = `ws://${host}:${port}${path}`;
    this.reconnectAttempts = 0;
    this.doConnect();
  }

  disconnect() {
    this.stopHeartbeat();
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
    this.ws?.close();
    this.ws = null;
    this.connected = false;
  }

  /** 发送 JSON header 帧（自动附加 Authorization token） */
  send(header: IMHeader, body?: Uint8Array) {
    if (this.ws?.readyState === WebSocket.OPEN) {
      const traceId = nextTraceId();
      header._seq = String(++this.seqCounter);
      header._ts = String(Date.now());
      header._traceId = traceId;
      // 自动附加 Authorization 头
      const token = localStorage.getItem("im_token");
      if (token) {
        header.Authorization = `Bearer ${token}`;
      }
      logDebug(traceId, 'ws.send', { op: header._op, seq: header._seq });
      this.ws.send(encodeFrame(header, body));
    } else {
      logWarn('noid', 'ws.send.fail', { reason: 'not connected' });
    }
  }

  /** 快速登录 */
  login(userId: string, password?: string) {
    const header: IMHeader = {
      _op: String(CMD.LOGIN),
      userId,
    };
    if (password) header.password = password;
    this.send(header);
  }

  /** 注册 */
  register(userId: string, password?: string, nickname?: string, faceUrl?: string) {
    const header: IMHeader = {
      _op: String(CMD.REGISTER),
      userId,
    };
    if (password) header.password = password;
    if (nickname) header.nickname = nickname;
    if (faceUrl) header.faceUrl = faceUrl;
    this.send(header);
  }

  /** 发送单聊消息 */
  sendMessage(toUserId: string, content: string, contentType = "1") {
    this.send({
      _op: String(CMD.SINGLE_CHAT),
      fromUserId: this.getUserId(),
      toUserId,
      contentType,
      content,
    });
  }

  /** 拉取会话列表 */
  fetchConversations() {
    this.send({
      _op: String(CMD.CONVERSATION_GET),
      userId: this.getUserId(),
    });
  }

  /** 拉取好友列表 */
  fetchFriendList() {
    this.send({
      _op: String(CMD.FRIEND_LIST),
      userId: this.getUserId(),
    });
  }

  /** 搜索用户 */
  searchUser(keyword: string, limit = 20) {
    this.send({ _op: String(CMD.USER_SEARCH), keyword, limit: String(limit), userId: this.getUserId() });
  }

  /** 搜索群组 */
  searchGroup(keyword: string, limit = 20) {
    this.send({ _op: String(CMD.GROUP_SEARCH), keyword, limit: String(limit), userId: this.getUserId() });
  }

  /** 申请加好友 */
  applyFriend(targetUserId: string, reqMsg?: string) {
    this.send({ _op: String(CMD.FRIEND_APPLY), userId: this.getUserId(), toUserId: targetUserId, ...(reqMsg ? { reqMsg } : {}) });
  }

  /** 上传文件（小文件，通过 WebSocket 以二进制帧发送） */
  uploadFile(file: File, onProgress?: (pct: number) => void): Promise<string> {
    return new Promise((resolve, reject) => {
      const reader = new FileReader();
      reader.onload = () => {
        const arrayBuffer = reader.result as ArrayBuffer;
        const body = new Uint8Array(arrayBuffer);

        // 用 onmessage 一次性监听 FILE_UPLOAD_ACK
        const handler = (frame: IMBinaryFrame | null) => {
          if (!frame) return;
          const op = parseInt(frame.header._op || "0");
          if (op === CMD.FILE_UPLOAD_ACK && frame.header.status === "OK") {
            resolve(frame.header.fileUrl);
          } else if (op === CMD.ERROR) {
            reject(new Error(`Upload failed: ${frame.header.reason || frame.header.detail}`));
          }
        };
        this.once(handler);

        this.send(
          { _op: String(CMD.FILE_UPLOAD), fileName: file.name, mimeType: file.type || "application/octet-stream" },
          body
        );
        if (onProgress) onProgress(100);
      };
      reader.onerror = () => reject(new Error("File read failed"));
      reader.readAsArrayBuffer(file);
    });
  }

  /** 同意好友申请 */
  approveFriend(fromUserId: string, agreed = true) {
    this.send({ _op: String(CMD.FRIEND_APPROVE), userId: this.getUserId(), fromUserId, agreed: String(agreed) });
  }

  /** 删除好友 */
  removeFriend(targetUserId: string) {
    this.send({ _op: String(CMD.FRIEND_REMOVE), userId: this.getUserId(), friendUserId: targetUserId });
  }

  /** 加入群组 */
  joinGroup(groupId: string, reqMsg?: string) {
    this.send({ _op: String(CMD.GROUP_JOIN), userId: this.getUserId(), groupId, ...(reqMsg ? { reqMsg } : {}) });
  }

  /** 退出群组 */
  quitGroup(groupId: string) {
    this.send({ _op: String(CMD.GROUP_QUIT), userId: this.getUserId(), groupId });
  }

  on(event: WsEventType, cb: WsEventListener) {
    if (!this.listeners.has(event)) {
      this.listeners.set(event, new Set());
    }
    this.listeners.get(event)!.add(cb);
    return () => this.listeners.get(event)?.delete(cb);
  }

  /** 一次性监听（自动取消注册） */
  once(event: WsEventType, cb: WsEventListener) {
    const wrapper: WsEventListener = (frame) => {
      cb(frame);
      this.off(event, wrapper);
    };
    this.on(event, wrapper);
  }

  off(event: WsEventType, cb: WsEventListener) {
    this.listeners.get(event)?.delete(cb);
  }

  emit(event: WsEventType, frame: IMBinaryFrame | null) {
    this.listeners.get(event)?.forEach((cb) => cb(frame));
  }

  private getUserId(): string {
    // 从本地存储取
    return localStorage.getItem("im_userId") || "";
  }

  private doConnect() {
    this.ws = new WebSocket(this.url);
    this.ws.binaryType = "arraybuffer";

    this.ws.onopen = () => {
      this.connected = true;
      this.reconnectAttempts = 0;
      this.startHeartbeat();
      this.emit("open", null);
    };

    this.ws.onclose = () => {
      this.connected = false;
      this.stopHeartbeat();
      this.emit("close", null);
      this.tryReconnect();
    };

    this.ws.onerror = () => {
      this.emit("error", null);
    };

    this.ws.onmessage = (event) => {
      // 记录 data 类型以便调试
      const dataType = typeof event.data;
      const ctorName = event.data?.constructor?.name ?? 'null';
      if (dataType === 'string' || (dataType === 'object' && ctorName !== 'ArrayBuffer' && ctorName !== 'Uint8Array')) {
        logWarn('noid', 'ws.unknownframe', { type: dataType, ctor: ctorName, len: event.data?.length ?? event.data?.size ?? '?' });
        return;
      }
      const raw = new Uint8Array(event.data);
      // 如果前 2 字节不是 0xACAC，打印前 40 字节的 hex 用于调试
      if (raw.length < 2 || raw[0] !== 0xAC || raw[1] !== 0xAC) {
        const hexPrefix = Array.from(raw.slice(0, 40)).map(b => b.toString(16).padStart(2, '0')).join(' ');
        const asText = raw.length > 0 ? new TextDecoder().decode(raw.slice(0, 40)) : '';
        logWarn('noid', 'ws.badframe', { len: raw.length, hex: hexPrefix, asText: asText.replace(/[\x00-\x1f\x7f-\x9f]/g, '?') });
        return;
      }
      this.buffer = concatBytes(this.buffer, raw);
    };
  }

  private tryReconnect() {
    if (this.reconnectAttempts >= this.maxReconnect) return;
    this.reconnectAttempts++;
    const delay = Math.min(1000 * Math.pow(2, this.reconnectAttempts), 30000);
    this.reconnectTimer = setTimeout(() => this.doConnect(), delay);
  }

  private startHeartbeat() {
    this.heartbeatTimer = setInterval(() => {
      this.send({ _op: String(CMD.HEARTBEAT) });
    }, 7000);
  }

  private stopHeartbeat() {
    if (this.heartbeatTimer) {
      clearInterval(this.heartbeatTimer);
      this.heartbeatTimer = null;
    }
  }
}

function concatBytes(a: Uint8Array, b: Uint8Array): Uint8Array {
  const result = new Uint8Array(a.length + b.length);
  result.set(a);
  result.set(b, a.length);
  return result;
}

/** 全局单例 */
export const imConnection = new IMConnection();
