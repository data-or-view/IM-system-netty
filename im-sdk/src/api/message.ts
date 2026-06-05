import {
  OP,
  SignalingAction,
  type Message,
  type OutgoingMessageContentTypeValue,
  type RevokeMessageParam,
  type SearchMessagesParam,
  type SearchMessagesResult,
  type SendMessageAck,
  type SendMessageParam,
  type StartCallAck,
  type StartCallParam,
} from "../types.js";
import type { WsTransport } from "../transport/ws.js";
import { type HttpAPI, requireHttp } from "./http-api.js";

/**
 * 消息模块 API。
 */
export class MessageAPI {
  constructor(private wsTransport: WsTransport, private httpTransport?: HttpAPI) {}

  /** 发送单聊消息：实时链路，走 WS */
  send(param: SendMessageParam): Promise<SendMessageAck> {
    return this.wsTransport.request(OP.CHAT_SEND, {
      toUserId: param.toUserId,
      _ct: param.contentType,
      content: param.content,
    }).then((r) => r.data as SendMessageAck);
  }

  /** 发送群聊消息：实时链路，走 WS */
  sendGroup(groupId: string, contentType: OutgoingMessageContentTypeValue, content: unknown): Promise<SendMessageAck> {
    return this.wsTransport.request(OP.CHAT_SEND_GROUP, {
      groupId,
      _ct: contentType,
      content,
    }).then((r) => r.data as SendMessageAck);
  }

  /** 发起语音/视频通话：信令走 WS，媒体走服务端返回的 SFU。 */
  startCall(param: StartCallParam): Promise<StartCallAck> {
    return this.wsTransport.request(OP.CHAT_SEND, {
      toUserId: param.toUserId,
      _ct: "signal",
      content: {
        action: SignalingAction.INVITE,
        callType: param.callType,
      },
    }).then((r) => r.data as StartCallAck);
  }

  /** 发送通话信令：接听、拒绝、取消、挂断等。 */
  sendCallSignal(toUserId: string, action: string, roomId: string, duration?: number): Promise<SendMessageAck> {
    return this.send({
      toUserId,
      contentType: "signal",
      content: {
        action,
        roomId,
        ...(duration !== undefined ? { duration } : {}),
      },
    });
  }

  /** 拉取历史消息 */
  pull(conversationId: string, startSeq: number, endSeq?: number): Promise<Message[]> {
    return requireHttp(this.httpTransport).post<{ messages?: Message[] } | Message[]>("/api/msg/pull", {
      conversationId,
      startSeq,
      ...(endSeq !== undefined ? { endSeq } : {}),
    }).then((data) => Array.isArray(data) ? data : data.messages ?? []);
  }

  /** 获取最新 seq */
  seq(conversationId: string): Promise<number> {
    return requireHttp(this.httpTransport).get<{ maxSeq?: number } | number>("/api/msg/seq", { conversationId })
      .then((data) => typeof data === "number" ? data : data.maxSeq ?? 0);
  }

  /** 增量同步 */
  sync(conversationId: string, lastSeq: number): Promise<Array<{ conversationId: string; messages: Message[]; maxSeq: number }>> {
    return requireHttp(this.httpTransport).post<{ syncs?: Array<{ conversationId: string; messages: Message[]; maxSeq: number }> }>("/api/msg/sync", {
      seqs: { [conversationId]: lastSeq },
    }).then((data) => data.syncs ?? []);
  }

  /** 搜索消息 */
  search(param: SearchMessagesParam): Promise<SearchMessagesResult> {
    const pageSize = param.pageSize ?? 20;
    const page = param.page ?? 1;
    return requireHttp(this.httpTransport).post<{ messages?: Message[]; totalCount?: number; total?: number; hasMore?: boolean }>("/api/msg/search", {
      conversationIds: [param.conversationId],
      keyword: param.keyword,
      ...(param.contentTypeFilter ? { contentTypeFilter: param.contentTypeFilter } : {}),
      ...(param.startTime ? { startTime: param.startTime } : {}),
      ...(param.endTime ? { endTime: param.endTime } : {}),
      limit: pageSize,
      offset: Math.max(page - 1, 0) * pageSize,
    }).then((data) => ({
      messages: data.messages ?? [],
      total: data.total ?? data.totalCount ?? 0,
      hasMore: data.hasMore ?? false,
    }));
  }

  /** 撤回消息 */
  revoke(param: RevokeMessageParam): Promise<void>;
  revoke(messageId: string): Promise<void>;
  revoke(param: RevokeMessageParam | string): Promise<void> {
    const payload = typeof param === "string"
      ? { messageId: param }
      : {
          conversationId: param.conversationId,
          messageSeq: param.messageSeq,
          ...(param.groupId ? { groupId: param.groupId } : {}),
        };
    return requireHttp(this.httpTransport).post("/api/msg/revoke", payload).then(() => undefined);
  }
}
