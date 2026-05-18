import { OP, type Message, type SearchMessagesParam, type SearchMessagesResult, type SendMessageParam } from "../types.js";
import type { WsTransport } from "../transport/ws.js";

/**
 * 消息模块 API。
 */
export class MessageAPI {
  constructor(private transport: WsTransport) {}

  /** 发送单聊消息 */
  send(param: SendMessageParam): Promise<Message> {
    const { frame, promise } = this.transport.requestManager.createRequest(OP.CHAT_SEND, {
      toUserId: param.toUserId,
      contentType: param.contentType,
      content: param.content,
    });
    this.transport.send(frame);
    return promise.then((r) => r.data as Message);
  }

  /** 发送群聊消息 */
  sendGroup(groupId: string, contentType: string, content: string): Promise<Message> {
    const { frame, promise } = this.transport.requestManager.createRequest(OP.CHAT_SEND_GROUP, {
      groupId,
      contentType,
      content,
    });
    this.transport.send(frame);
    return promise.then((r) => r.data as Message);
  }

  /** 拉取历史消息 */
  pull(conversationId: string, startSeq: number, endSeq?: number): Promise<Message[]> {
    const { frame, promise } = this.transport.requestManager.createRequest(OP.CHAT_PULL, {
      conversationId,
      startSeq,
      ...(endSeq !== undefined ? { endSeq } : {}),
    });
    this.transport.send(frame);
    return promise.then((r) => r.data as Message[]);
  }

  /** 获取最新 seq */
  seq(conversationId: string): Promise<number> {
    const { frame, promise } = this.transport.requestManager.createRequest(OP.CHAT_SEQ, {
      conversationId,
    });
    this.transport.send(frame);
    return promise.then((r) => r.data as number);
  }

  /** 增量同步 */
  sync(conversationId: string, lastSeq: number): Promise<Message[]> {
    const { frame, promise } = this.transport.requestManager.createRequest(OP.CHAT_SYNC, {
      conversationId,
      lastSeq,
    });
    this.transport.send(frame);
    return promise.then((r) => r.data as Message[]);
  }

  /** 搜索消息 */
  search(param: SearchMessagesParam): Promise<SearchMessagesResult> {
    const { frame, promise } = this.transport.requestManager.createRequest(OP.CHAT_SEARCH, {
      conversationId: param.conversationId,
      keyword: param.keyword,
      ...(param.contentTypeFilter ? { contentTypeFilter: param.contentTypeFilter } : {}),
      ...(param.startTime ? { startTime: param.startTime } : {}),
      ...(param.endTime ? { endTime: param.endTime } : {}),
      ...(param.pageSize ? { pageSize: param.pageSize } : {}),
      ...(param.page !== undefined ? { page: param.page } : {}),
    });
    this.transport.send(frame);
    return promise.then((r) => r.data as SearchMessagesResult);
  }

  /** 撤回消息 */
  revoke(messageId: string): Promise<void> {
    const { frame, promise } = this.transport.requestManager.createRequest(OP.CHAT_REVOKE, {
      messageId,
    });
    this.transport.send(frame);
    return promise.then(() => undefined);
  }
}
