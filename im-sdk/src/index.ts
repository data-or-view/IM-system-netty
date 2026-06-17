/**
 * IM SDK — 纯 TypeScript WebSocket SDK，零依赖。
 *
 * # 快速开始
 *
 * ```ts
 * import { createIM } from "im-sdk";
 *
 * const im = createIM({ wsUrl: "ws://localhost:8081/ws" });
 *
 * // 监听连接状态
 * im.on("connectionStateChanged", (state) => console.log(state));
 *
 * // 连接
 * im.connect();
 *
 * // 登录
 * await im.user.login("user_001");
 *
 * // 搜索用户
 * const users = await im.friend.search("abc");
 *
 * // 发消息
 * const msg = await im.message.send({
 *   toUserId: "user_002",
 *   contentType: "text",
 *   content: { text: "Hello!" },
 * });
 * ```
 *
 * @module im-sdk
 */

import { EventBus } from "./event-bus.js";

// ── 导出类型 ──
export * from "./types.js";
export { createClientMsgId } from "./protocol/client-msg-id.js";

// ── 内部导入 ──
import { type ConnectionState, type IMEvents, type IMOptions, type Message, type FriendApply, type GroupApply, type ReconnectSyncResult, type SystemMessageSummary, type MessageRevoked, type TokenPair, type WSPush, IMError, IMTimeoutError, PUSH_OP, toIMError } from "./types.js";
import { WsTransport } from "./transport/ws.js";
import { HttpTransport } from "./transport/http.js";
import { UserAPI } from "./api/user.js";
import { FriendAPI } from "./api/friend.js";
import { GroupAPI } from "./api/group.js";
import { MessageAPI } from "./api/message.js";
import { ConversationAPI } from "./api/conversation.js";
import { FileAPI } from "./api/file.js";
import { SystemAPI } from "./api/system.js";

// ── SDK 主类 ──

export class IMSDK {
  /** 用户模块 */
  user: UserAPI;
  /** 好友模块 */
  friend: FriendAPI;
  /** 群组模块 */
  group: GroupAPI;
  /** 消息模块 */
  message: MessageAPI;
  /** 会话模块 */
  conversation: ConversationAPI;
  /** 文件模块 */
  file: FileAPI;
  /** 系统通知模块 */
  system: SystemAPI;

  private transport: WsTransport;
  private httpTransport?: HttpTransport;
  private getToken: () => string | null;
  private getRefreshToken: () => string | null;
  private accessToken: string | null = null;
  private refreshTokenValue: string | null = null;
  private bus = new EventBus();
  private messageBatch: Message[] = [];
  private messageBatchTimer: ReturnType<typeof setTimeout> | null = null;
  private messageBatchInterval: number;
  private messageBatchSize: number;
  private seenMessageKeys: string[] = [];
  private seenMessageKeySet = new Set<string>();
  private connectTimeout: number;
  private wasConnected = false;
  private reconnectSyncRunning = false;
  private readonly maxSeenMessageKeys = 1000;

  constructor(private opts: IMOptions) {
    this.getToken = () => this.accessToken ?? opts.getToken?.() ?? null;
    this.getRefreshToken = () => this.refreshTokenValue ?? opts.getRefreshToken?.() ?? null;
    this.messageBatchInterval = opts.messageBatchInterval ?? 16;
    this.messageBatchSize = Math.max(1, opts.messageBatchSize ?? 100);
    this.connectTimeout = opts.connectTimeout ?? 10000;
    this.transport = new WsTransport({
      getToken: this.getToken,
      getRefreshToken: this.getRefreshToken,
      onTokenChanged: (tokens) => this.applyTokens(tokens),
      maxReconnect: opts.maxReconnect,
      heartbeatInterval: opts.heartbeatInterval,
      requestTimeout: opts.requestTimeout,
      requestIdFactory: opts.requestIdFactory,
    });
    this.httpTransport = opts.httpUrl ? new HttpTransport({
      baseUrl: opts.httpUrl,
      getToken: this.getToken,
      requestIdFactory: opts.requestIdFactory,
      requestTimeout: opts.requestTimeout,
    }) : undefined;

    this.file = new FileAPI(this.httpTransport);
    this.user = new UserAPI(this.transport, this.httpTransport, (fileName, fileData, mimeType) =>
      this.file.upload(fileName, fileData, mimeType)
    );
    this.friend = new FriendAPI(this.httpTransport);
    this.group = new GroupAPI(this.httpTransport);
    this.message = new MessageAPI(this.transport, this.httpTransport);
    this.conversation = new ConversationAPI(this.httpTransport);
    this.system = new SystemAPI(this.httpTransport);

    // 转发 push 事件到 SDK 级别 listener
    this.transport.bus.on("push", (raw: unknown) => {
      const push = raw as WSPush;
      this.bus.emit("push", push);
      if (push.op === PUSH_OP.MESSAGE) {
        this.emitMessage(push.data as Message);
      } else if (push.op === PUSH_OP.FRIEND_APPLY) {
        this.bus.emit("friendRequest", push.data as FriendApply);
      } else if (push.op === PUSH_OP.GROUP_APPLY) {
        this.bus.emit("groupApply", push.data as GroupApply);
      } else if (push.op === PUSH_OP.SYSTEM_MESSAGE) {
        this.bus.emit("systemMessage", push.data as SystemMessageSummary);
      } else if (push.op === PUSH_OP.MESSAGE_REVOKED) {
        this.bus.emit("messageRevoked", push.data as MessageRevoked);
      }
    });

    // 转发连接状态变化
    this.transport.bus.on("stateChanged", (state: unknown) => {
      const connectionState = state as ConnectionState;
      this.bus.emit("connectionStateChanged", connectionState);
      if (connectionState === "connected") {
        void this.handleConnected();
      }
    });

    // 转发错误
    this.transport.bus.on("error", (err: unknown) => {
      this.bus.emit("error", err as IMError);
    });
  }

  /**
   * 订阅 SDK 事件。
   *
   * @example
   * ```ts
   * im.on("message", (msg) => console.log("新消息:", msg));
   * im.on("connectionStateChanged", (state) => console.log("状态:", state));
   * ```
   */
  on<K extends keyof IMEvents>(event: K, listener: IMEvents[K]): () => void {
    return this.bus.on(event, listener as (...args: unknown[]) => void);
  }

  /** 当前连接状态 */
  get state(): ConnectionState {
    return this.transport.state;
  }

  get token(): string | null {
    return this.accessToken ?? this.opts.getToken?.() ?? null;
  }

  get refreshToken(): string | null {
    return this.refreshTokenValue ?? this.opts.getRefreshToken?.() ?? null;
  }

  async login(userId: string, password?: string): Promise<TokenPair> {
    const response = await this.user.login(userId, password);
    const tokens = response.data as TokenPair | undefined;
    return this.applyTokens(tokens ?? {});
  }

  /** 清空 SDK 内存中的 token。宿主应用仍负责清理自己的持久化存储。 */
  clearTokens(): void {
    this.accessToken = null;
    this.refreshTokenValue = null;
    this.opts.onTokenChanged?.({});
    this.bus.emit("tokenChanged", {});
  }

  // ── 连接管理 ──

  /** 建立 WebSocket 连接 */
  connect(): void {
    this.transport.connect(this.opts.wsUrl);
  }

  /** 主动连接并等待 WebSocket 可用。 */
  ready(timeoutMs = this.connectTimeout): Promise<void> {
    this.connect();
    return this.waitConnected(timeoutMs);
  }

  /** 等待 WebSocket 进入 connected 状态，不主动发起连接。 */
  waitConnected(timeoutMs = this.connectTimeout): Promise<void> {
    if (this.transport.connected) {
      return Promise.resolve();
    }
    return new Promise((resolve, reject) => {
      const unsubscribe = this.on("connectionStateChanged", (state) => {
        if (state === "connected") {
          cleanup();
          resolve();
        }
      });
      const timer = setTimeout(() => {
        cleanup();
        reject(new IMTimeoutError(-1, "Wait connected timeout"));
      }, timeoutMs);
      const cleanup = () => {
        clearTimeout(timer);
        unsubscribe();
      };
    });
  }

  /** 断开连接 */
  disconnect(): void {
    this.flushMessageBatch();
    this.wasConnected = false;
    this.transport.disconnect();
  }

  private applyTokens(tokens: TokenPair): TokenPair {
    if (tokens.token) {
      this.accessToken = tokens.token;
    }
    if (tokens.refreshToken) {
      this.refreshTokenValue = tokens.refreshToken;
    }
    const current = {
      ...(this.accessToken ? { token: this.accessToken } : {}),
      ...(this.refreshTokenValue ? { refreshToken: this.refreshTokenValue } : {}),
      ...(tokens.expiresIn !== undefined ? { expiresIn: tokens.expiresIn } : {}),
    };
    this.opts.onTokenChanged?.(current);
    this.bus.emit("tokenChanged", current);
    return current;
  }

  private async handleConnected(): Promise<void> {
    const isReconnect = this.wasConnected;
    this.wasConnected = true;
    if (!isReconnect || !this.opts.syncOnReconnect || this.reconnectSyncRunning) {
      return;
    }
    this.reconnectSyncRunning = true;
    try {
      await this.syncAfterReconnect();
    } catch (err) {
      this.bus.emit("error", toIMError(err, "Reconnect sync failed"));
    } finally {
      this.reconnectSyncRunning = false;
    }
  }

  private async syncAfterReconnect(): Promise<void> {
    const cursors = await this.opts.syncConversations?.();
    if (!cursors?.length) {
      return;
    }
    for (const cursor of cursors) {
      const syncs = await this.message.sync(cursor.conversationId, cursor.lastSeq);
      for (const result of syncs) {
        this.emitReconnectSync(result);
      }
    }
  }

  private emitReconnectSync(result: ReconnectSyncResult): void {
    if (result.messages.length > 0) {
      for (const msg of result.messages) {
        this.emitMessage(msg);
      }
    }
    this.bus.emit("reconnectSync", result);
  }

  private emitMessage(msg: Message): void {
    if (this.hasSeenMessage(msg)) {
      return;
    }
    this.bus.emit("message", msg);
    this.messageBatch.push(msg);
    if (this.messageBatch.length >= this.messageBatchSize || this.messageBatchInterval <= 0) {
      this.flushMessageBatch();
      return;
    }
    if (!this.messageBatchTimer) {
      this.messageBatchTimer = setTimeout(() => this.flushMessageBatch(), this.messageBatchInterval);
    }
  }

  private flushMessageBatch(): void {
    if (this.messageBatchTimer) {
      clearTimeout(this.messageBatchTimer);
      this.messageBatchTimer = null;
    }
    if (this.messageBatch.length === 0) {
      return;
    }
    const batch = this.messageBatch;
    this.messageBatch = [];
    this.bus.emit("messageBatch", batch);
  }

  private hasSeenMessage(msg: Message): boolean {
    const key = messageDedupeKey(msg);
    if (!key) {
      return false;
    }
    if (this.seenMessageKeySet.has(key)) {
      return true;
    }
    this.seenMessageKeySet.add(key);
    this.seenMessageKeys.push(key);
    while (this.seenMessageKeys.length > this.maxSeenMessageKeys) {
      const removed = this.seenMessageKeys.shift();
      if (removed) {
        this.seenMessageKeySet.delete(removed);
      }
    }
    return false;
  }
}

// ── 工厂函数 ──

/**
 * 创建 IM SDK 实例。
 *
 * @param opts 配置选项
 *
 * @example
 * ```ts
 * const im = createIM({
 *   wsUrl: "ws://localhost:8081/ws",
 *   getToken: () => localStorage.getItem("im_token"),
 * });
 * im.connect();
 * ```
 */
export function createIM(opts: IMOptions): IMSDK {
  return new IMSDK(opts);
}

function messageDedupeKey(msg: Message): string | null {
  if (msg.messageId) {
    return `id:${msg.messageId}`;
  }
  const seq = msg.messageSeq ?? msg.sequenceId;
  if (msg.conversationId && seq && seq > 0) {
    return `seq:${msg.conversationId}:${seq}`;
  }
  return null;
}
