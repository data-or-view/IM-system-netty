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
 *   contentType: "1",
 *   content: "Hello!",
 * });
 * ```
 *
 * @module im-sdk
 */

import { EventBus } from "./event-bus.js";

// ── 导出类型 ──
export * from "./types.js";

// ── 内部导入 ──
import { type ConnectionState, type IMEvents, type IMOptions, type Message, type FriendApply, type WSResponse, type WSPush, IMError, PUSH_OP } from "./types.js";
import { WsTransport } from "./transport/ws.js";
import { UserAPI } from "./api/user.js";
import { FriendAPI } from "./api/friend.js";
import { GroupAPI } from "./api/group.js";
import { MessageAPI } from "./api/message.js";
import { ConversationAPI } from "./api/conversation.js";
import { FileAPI } from "./api/file.js";

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

  private transport: WsTransport;
  private getToken: () => string | null;
  private bus = new EventBus();

  constructor(private opts: IMOptions) {
    this.getToken = opts.getToken || (() => null);
    this.transport = new WsTransport({
      getToken: this.getToken,
      maxReconnect: opts.maxReconnect,
      heartbeatInterval: opts.heartbeatInterval,
      requestTimeout: opts.requestTimeout,
    });

    this.user = new UserAPI(this.transport);
    this.friend = new FriendAPI(this.transport);
    this.group = new GroupAPI(this.transport);
    this.message = new MessageAPI(this.transport);
    this.conversation = new ConversationAPI(this.transport);
    this.file = new FileAPI(this.transport);

    // 转发 push 事件到 SDK 级别 listener
    this.transport.bus.on("push", (raw: unknown) => {
      const push = raw as WSPush;
      if (push.op === PUSH_OP.MESSAGE) {
        this.bus.emit("message", push.data as Message);
      } else if (push.op === PUSH_OP.FRIEND_APPLY) {
        this.bus.emit("friendRequest", push.data as FriendApply);
      }
    });

    // 转发连接状态变化
    this.transport.bus.on("stateChanged", (state: unknown) => {
      this.bus.emit("connectionStateChanged", state as ConnectionState);
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

  // ── 连接管理 ──

  /** 建立 WebSocket 连接 */
  connect(): void {
    this.transport.connect(this.opts.wsUrl);
  }

  /** 断开连接 */
  disconnect(): void {
    this.transport.disconnect();
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
