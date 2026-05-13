/**
 * WebSocket 连接管理器。
 *
 * 管理到 IM Server 的 WebSocket 连接：
 *   ① 自动心跳（7 秒间隔）
 *   ② 二进制帧编解码
 *   ③ 事件通知
 */

import { type IMHeader, type IMBinaryFrame, encodeFrame, decodeFrame, CMD } from "./protocol";

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

  /** 发送 JSON header 帧 */
  send(header: IMHeader, body?: Uint8Array) {
    if (this.ws?.readyState === WebSocket.OPEN) {
      header._seq = String(++this.seqCounter);
      header._ts = String(Date.now());
      this.ws.send(encodeFrame(header, body));
    }
  }

  /** 快速登录 */
  login(userId: string) {
    this.send({
      _op: String(CMD.LOGIN),
      userId,
    });
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

  /** 获取好友列表 */
  fetchFriendList() {
    this.send({
      _op: String(CMD.FRIEND_LIST),
      userId: this.getUserId(),
    });
  }

  on(event: WsEventType, cb: WsEventListener) {
    if (!this.listeners.has(event)) {
      this.listeners.set(event, new Set());
    }
    this.listeners.get(event)!.add(cb);
    return () => this.listeners.get(event)?.delete(cb);
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
      const raw = new Uint8Array(event.data);
      this.buffer = concatBytes(this.buffer, raw);

      while (this.buffer.length > 0) {
        try {
          const result = decodeFrame(this.buffer);
          if (!result) break;
          const [frame, rest] = result;
          this.buffer = rest;
          this.emit("message", frame);
        } catch {
          // 解析失败，清空 buffer 避免死循环
          this.buffer = new Uint8Array(0);
        }
      }
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
