import { type ConnectionState, type TokenPair, type WSResponse, type WSPush, PUSH_OP, IMConnectionError, IMError } from "../types.js";
import { EventBus } from "../event-bus.js";
import { RequestManager } from "../protocol/request-manager.js";

export type WsEvents = {
  response: (resp: WSResponse) => void;
  push: (push: WSPush) => void;
  stateChanged: (state: ConnectionState) => void;
  error: (err: IMError) => void;
};

/**
 * WebSocket 传输层。
 *
 * 职责：
 *  - 连接 / 断连 / 自动重连（指数退避）
 *  - 发送 JSON 帧
 *  - 接收并分发：响应帧 → RequestManager，推送帧 → 订阅者
 *  - 心跳维持
 */
export class WsTransport {
  private ws: WebSocket | null = null;
  private url = "";
  private getToken: () => string | null;
  private getRefreshToken: () => string | null;
  private onTokenChanged?: (tokens: TokenPair) => void;
  private maxReconnect: number;
  private heartbeatInterval: number;

  private reconnectAttempts = 0;
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  private heartbeatTimer: ReturnType<typeof setInterval> | null = null;
  private intentionalClose = false;

  private _state: ConnectionState = "disconnected";
  private reqManager: RequestManager;

  bus = new EventBus();
  // 类型安全的 emit 包装
  private emitPush = (push: WSPush) => this.bus.emit("push", push);
  private emitError = (err: IMError) => this.bus.emit("error", err);

  constructor(
    opts: {
      getToken?: () => string | null;
      getRefreshToken?: () => string | null;
      onTokenChanged?: (tokens: TokenPair) => void;
      maxReconnect?: number;
      heartbeatInterval?: number;
      requestTimeout?: number;
      requestIdFactory?: () => string;
    },
  ) {
    this.getToken = opts.getToken || (() => null);
    this.getRefreshToken = opts.getRefreshToken || (() => null);
    this.onTokenChanged = opts.onTokenChanged;
    this.maxReconnect = opts.maxReconnect ?? 10;
    this.heartbeatInterval = opts.heartbeatInterval ?? 7000;
    this.reqManager = new RequestManager(opts.requestTimeout);
    this.reqManager.requestIdFactory = opts.requestIdFactory;
  }

  get state(): ConnectionState {
    return this._state;
  }

  get connected(): boolean {
    return this.ws?.readyState === WebSocket.OPEN && this._state === "connected";
  }

  get requestManager(): RequestManager {
    return this.reqManager;
  }

  // ── 生命周期 ──

  connect(url: string): void {
    if (this.ws && (this.ws.readyState === WebSocket.OPEN || this.ws.readyState === WebSocket.CONNECTING)) {
      return; // 已连接或正在连接
    }
    this.url = url;
    this.intentionalClose = false;
    this.reconnectAttempts = 0;
    this.doConnect();
  }

  disconnect(): void {
    this.intentionalClose = true;
    this.stopHeartbeat();
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
    if (this.ws) {
      this.ws.close();
      this.ws = null;
    }
    this.reqManager.rejectAll("Connection closed");
    this.setState("disconnected");
  }

  // ── 发送 ──

  send(frame: Record<string, unknown>): boolean {
    if (this.ws?.readyState !== WebSocket.OPEN) {
      this.emitError(new IMConnectionError());
      return false;
    }
    // 自动注入 Authorization token
    const token = this.getToken();
    if (token) {
      frame.Authorization = token;
    }
    if (frame.op === "heartbeat") {
      const refreshToken = this.getRefreshToken();
      if (refreshToken) {
        frame.refreshToken = refreshToken;
      }
    }
    this.ws.send(JSON.stringify(frame));
    return true;
  }

  request(op: string, params: Record<string, unknown> = {}): Promise<WSResponse> {
    const { frame, promise } = this.reqManager.createRequest(op, params);
    if (!this.send(frame)) {
      this.reqManager.reject(frame.seq, new IMConnectionError());
    }
    return promise;
  }

  sendRaw(data: string): void {
    if (this.ws?.readyState === WebSocket.OPEN) {
      this.ws.send(data);
    }
  }

  // ── 内部 ──

  private doConnect(): void {
    this.setState("connecting");
    this.ws = new WebSocket(this.url);
    this.ws.onopen = () => {
      this.setState("connected");
      this.reconnectAttempts = 0;
      this.startHeartbeat();
    };
    this.ws.onclose = () => {
      this.setState("disconnected");
      this.stopHeartbeat();
      if (!this.intentionalClose) {
        this.tryReconnect();
      }
    };
    this.ws.onerror = () => {
      // onclose will fire after onerror
    };
    this.ws.onmessage = (event) => {
      if (typeof event.data !== "string") return;
      this.handleMessage(event.data);
    };
  }

  private handleMessage(raw: string): void {
    let parsed: Record<string, unknown>;
    try {
      parsed = JSON.parse(raw);
    } catch {
      return;
    }

    // 判断是响应（有 seq 字段）还是推送（无 seq 字段）
    if (typeof parsed.seq === "number") {
      const resp = parsed as unknown as WSResponse;
      this.handleTokenRefresh(resp);
      if (!this.reqManager.resolveResponse(resp)) {
        // 无匹配的 pending：可能是旧响应或广播
      }
    } else {
      this.emitPush(this.toPush(parsed));
    }
  }

  private toPush(parsed: Record<string, unknown>): WSPush {
    if (typeof parsed.op === "string") {
      return parsed as unknown as WSPush;
    }
    if (typeof parsed.messageId === "string" && typeof parsed.conversationId === "string") {
      return {
        op: PUSH_OP.MESSAGE,
        data: parsed,
      };
    }
    return {
      op: "unknown",
      data: parsed,
    };
  }

  private setState(state: ConnectionState): void {
    this._state = state;
    this.bus.emit("stateChanged", state);
  }

  private handleTokenRefresh(resp: WSResponse): void {
    if (resp.code !== 0 || resp.op !== "heartbeat_ack" || !resp.data || typeof resp.data !== "object") {
      return;
    }
    const data = resp.data as TokenPair;
    if (data.token || data.refreshToken) {
      this.onTokenChanged?.({
        ...(data.token ? { token: data.token } : {}),
        ...(data.refreshToken ? { refreshToken: data.refreshToken } : {}),
        ...(data.expiresIn !== undefined ? { expiresIn: data.expiresIn } : {}),
      });
    }
  }

  private tryReconnect(): void {
    if (this.reconnectAttempts >= this.maxReconnect) return;
    this.reconnectAttempts++;
    this.reqManager.rejectAll(`Reconnecting (attempt ${this.reconnectAttempts})`);
    this.setState("reconnecting");
    const delay = Math.min(1000 * Math.pow(2, this.reconnectAttempts), 30000);
    this.reconnectTimer = setTimeout(() => this.doConnect(), delay);
  }

  private startHeartbeat(): void {
    this.heartbeatTimer = setInterval(() => {
      this.send({ op: "heartbeat", seq: 0, _requestId: this.reqManager.nextRequestId() });
    }, this.heartbeatInterval);
  }

  private stopHeartbeat(): void {
    if (this.heartbeatTimer) {
      clearInterval(this.heartbeatTimer);
      this.heartbeatTimer = null;
    }
  }
}
