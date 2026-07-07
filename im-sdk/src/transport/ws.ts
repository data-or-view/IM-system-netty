import { type ConnectionState, type TokenPair, type WSResponse, type WSPush, PUSH_OP, IMConnectionError, IMError } from "../types.js";
import { EventBus } from "../event-bus.js";
import { RequestManager } from "../protocol/request-manager.js";
import { SDK_DEFAULTS } from "../config/defaults.js";
import { ACK_OP, PROTOCOL_SUCCESS_CODE, WS_FRAME_FIELD, WS_HEARTBEAT_SEQ } from "../protocol/constants.js";
import { OP } from "../protocol/ops.js";

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
  private connectionGeneration = 0;

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
    this.maxReconnect = opts.maxReconnect ?? SDK_DEFAULTS.maxReconnect;
    this.heartbeatInterval = opts.heartbeatInterval ?? SDK_DEFAULTS.heartbeatIntervalMs;
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
    this.connectionGeneration++;
    this.doConnect(this.connectionGeneration);
  }

  disconnect(): void {
    this.intentionalClose = true;
    this.connectionGeneration++;
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
      frame[WS_FRAME_FIELD.AUTHORIZATION] = token;
    }
    if (frame.op === OP.HEARTBEAT) {
      const refreshToken = this.getRefreshToken();
      if (refreshToken) {
        frame[WS_FRAME_FIELD.REFRESH_TOKEN] = refreshToken;
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

  private doConnect(generation = this.connectionGeneration): void {
    if (generation !== this.connectionGeneration) {
      return;
    }
    this.setState("connecting");
    const ws = new WebSocket(this.url);
    this.ws = ws;
    ws.onopen = () => {
      if (!this.isCurrentConnection(ws, generation)) return;
      this.setState("connected");
      this.reconnectAttempts = 0;
      this.startHeartbeat();
      this.sendHeartbeat();
    };
    ws.onclose = () => {
      if (!this.isCurrentConnection(ws, generation)) return;
      this.setState("disconnected");
      this.stopHeartbeat();
      if (!this.intentionalClose) {
        this.tryReconnect(generation);
      }
    };
    ws.onerror = () => {
      if (!this.isCurrentConnection(ws, generation)) return;
      // onclose will fire after onerror
    };
    ws.onmessage = (event) => {
      if (!this.isCurrentConnection(ws, generation)) return;
      if (typeof event.data !== "string") return;
      this.handleMessage(event.data);
    };
  }

  private isCurrentConnection(ws: WebSocket, generation: number): boolean {
    return generation === this.connectionGeneration && this.ws === ws && !this.intentionalClose;
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
    if (resp.code !== PROTOCOL_SUCCESS_CODE || resp.op !== ACK_OP.HEARTBEAT || !resp.data || typeof resp.data !== "object") {
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

  private tryReconnect(generation = this.connectionGeneration): void {
    if (generation !== this.connectionGeneration) return;
    if (this.reconnectAttempts >= this.maxReconnect) return;
    this.reconnectAttempts++;
    this.reqManager.rejectAll(`Reconnecting (attempt ${this.reconnectAttempts})`);
    this.setState("reconnecting");
    const delay = Math.min(
      SDK_DEFAULTS.reconnectBackoffBaseMs * Math.pow(2, this.reconnectAttempts),
      SDK_DEFAULTS.reconnectBackoffMaxMs,
    );
    this.reconnectTimer = setTimeout(() => this.doConnect(generation), delay);
  }

  private startHeartbeat(): void {
    this.stopHeartbeat();
    this.heartbeatTimer = setInterval(() => {
      this.sendHeartbeat();
    }, this.heartbeatInterval);
  }

  private sendHeartbeat(): void {
    this.send({
      op: OP.HEARTBEAT,
      seq: WS_HEARTBEAT_SEQ,
      [WS_FRAME_FIELD.REQUEST_ID]: this.reqManager.nextRequestId(),
    });
  }

  private stopHeartbeat(): void {
    if (this.heartbeatTimer) {
      clearInterval(this.heartbeatTimer);
      this.heartbeatTimer = null;
    }
  }
}
