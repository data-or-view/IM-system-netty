import { OP, type Conversation, type WSResponse } from "../types.js";
import type { WsTransport } from "../transport/ws.js";

/**
 * 会话模块 API。
 */
export class ConversationAPI {
  constructor(private transport: WsTransport) {}

  /** 获取会话列表 */
  list(): Promise<Conversation[]> {
    const { frame, promise } = this.transport.requestManager.createRequest(OP.CONVERSATION_LIST);
    this.transport.send(frame);
    return promise.then((r) => r.data as Conversation[]);
  }

  /** 更新会话设置（置顶、免打扰等） */
  set(conversationId: string, params: Record<string, unknown>): Promise<void> {
    const { frame, promise } = this.transport.requestManager.createRequest(OP.CONVERSATION_SET, {
      conversationId,
      ...params,
    });
    this.transport.send(frame);
    return promise.then(() => undefined);
  }

  /** 标记会话已读 */
  read(conversationId: string, seq?: number): Promise<void> {
    const { frame, promise } = this.transport.requestManager.createRequest(OP.CONVERSATION_READ, {
      conversationId,
      ...(seq !== undefined ? { seq } : {}),
    });
    this.transport.send(frame);
    return promise.then(() => undefined);
  }
}
