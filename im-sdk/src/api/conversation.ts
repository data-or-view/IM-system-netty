import { ConversationType, type Conversation, type ConversationReadResult } from "../types.js";
import { type HttpAPI, requireHttp } from "./http-api.js";

/**
 * 会话模块 API。
 */
export class ConversationAPI {
  constructor(private transport?: HttpAPI) {}

  /** 获取会话列表 */
  list(): Promise<Conversation[]> {
    return requireHttp(this.transport).get<{ conversations: Conversation[] }>("/api/conversation/list")
      .then((data) => data.conversations.map(normalizeConversation));
  }

  /** 更新会话设置（置顶、免打扰等） */
  set(conversationId: string, params: Record<string, unknown>): Promise<void> {
    return requireHttp(this.transport).post("/api/conversation/set", {
      conversationId,
      ...params,
    }).then(() => undefined);
  }

  /** 标记会话已读 */
  read(conversationId: string, seq?: number): Promise<ConversationReadResult> {
    return requireHttp(this.transport).post<ConversationReadResult>("/api/conversation/read", {
      conversationId,
      ...(seq !== undefined ? { readSeq: seq } : {}),
    });
  }
}

function normalizeConversation(conversation: Conversation): Conversation {
  const rawType = (conversation as unknown as { conversationType?: unknown }).conversationType;
  if (rawType === 1 || rawType === "1") {
    return { ...conversation, conversationType: ConversationType.SINGLE };
  }
  if (rawType === 2 || rawType === "2") {
    return { ...conversation, conversationType: ConversationType.GROUP };
  }
  return conversation;
}
