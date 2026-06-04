import { type Conversation } from "../types.js";
import { type HttpAPI, requireHttp } from "./http-api.js";

/**
 * 会话模块 API。
 */
export class ConversationAPI {
  constructor(private transport?: HttpAPI) {}

  /** 获取会话列表 */
  list(): Promise<Conversation[]> {
    return requireHttp(this.transport).get<{ conversations?: Conversation[] }>("/api/conversation/list")
      .then((data) => data.conversations ?? []);
  }

  /** 更新会话设置（置顶、免打扰等） */
  set(conversationId: string, params: Record<string, unknown>): Promise<void> {
    return requireHttp(this.transport).post("/api/conversation/set", {
      conversationId,
      ...params,
    }).then(() => undefined);
  }

  /** 标记会话已读 */
  read(conversationId: string, seq?: number): Promise<void> {
    return requireHttp(this.transport).post("/api/conversation/read", {
      conversationId,
      ...(seq !== undefined ? { readSeq: seq } : {}),
    }).then(() => undefined);
  }
}
