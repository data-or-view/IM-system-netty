import { OP, type FriendInfo, type FriendApply, type WSResponse } from "../types.js";
import type { WsTransport } from "../transport/ws.js";

/**
 * 好友模块 API。
 */
export class FriendAPI {
  constructor(private transport: WsTransport) {}

  /** 获取好友列表 */
  list(): Promise<FriendInfo[]> {
    const { frame, promise } = this.transport.requestManager.createRequest(OP.FRIEND_LIST);
    this.transport.send(frame);
    return promise.then((r) => r.data as FriendInfo[]);
  }

  /** 搜索用户（添加好友前搜索） */
  search(keyword: string, limit = 20): Promise<FriendInfo[]> {
    const { frame, promise } = this.transport.requestManager.createRequest(OP.USER_SEARCH, {
      keyword,
      limit,
    });
    this.transport.send(frame);
    return promise.then((r) => r.data as FriendInfo[]);
  }

  /** 申请加好友 */
  apply(targetUserId: string, reqMsg?: string): Promise<void> {
    const { frame, promise } = this.transport.requestManager.createRequest(OP.FRIEND_APPLY, {
      toUserId: targetUserId,
      ...(reqMsg ? { reqMsg } : {}),
    });
    this.transport.send(frame);
    return promise.then(() => undefined);
  }

  /** 审批好友申请 */
  approve(fromUserId: string, agreed: boolean): Promise<void> {
    const { frame, promise } = this.transport.requestManager.createRequest(OP.FRIEND_APPROVE, {
      fromUserId,
      agreed: String(agreed),
    });
    this.transport.send(frame);
    return promise.then(() => undefined);
  }

  /** 删除好友 */
  remove(friendUserId: string): Promise<void> {
    const { frame, promise } = this.transport.requestManager.createRequest(OP.FRIEND_REMOVE, {
      toUserId: friendUserId,
    });
    this.transport.send(frame);
    return promise.then(() => undefined);
  }

  /** 拉黑 */
  black(targetUserId: string): Promise<void> {
    const { frame, promise } = this.transport.requestManager.createRequest(OP.FRIEND_BLACK, {
      toUserId: targetUserId,
    });
    this.transport.send(frame);
    return promise.then(() => undefined);
  }

  /** 取消拉黑 */
  unblack(targetUserId: string): Promise<void> {
    const { frame, promise } = this.transport.requestManager.createRequest(OP.FRIEND_UNBLACK, {
      toUserId: targetUserId,
    });
    this.transport.send(frame);
    return promise.then(() => undefined);
  }

  /** 黑名单列表 */
  blacklist(): Promise<FriendInfo[]> {
    const { frame, promise } = this.transport.requestManager.createRequest(OP.FRIEND_BLACKLIST);
    this.transport.send(frame);
    return promise.then((r) => r.data as FriendInfo[]);
  }

  /** 已发送的好友申请列表 */
  sentApplyList(): Promise<FriendApply[]> {
    const { frame, promise } = this.transport.requestManager.createRequest(OP.FRIEND_APPLY_SENT);
    this.transport.send(frame);
    return promise.then((r) => r.data as FriendApply[]);
  }

  /** 好友申请详情 */
  applyDetail(fromUserId: string, toUserId: string): Promise<FriendApply> {
    const { frame, promise } = this.transport.requestManager.createRequest(OP.FRIEND_APPLY_DETAIL, {
      fromUserId,
      toUserId,
    });
    this.transport.send(frame);
    return promise.then((r) => r.data as FriendApply);
  }

  /** 未处理的好友申请数量 */
  unhandledApplyCount(): Promise<number> {
    const { frame, promise } = this.transport.requestManager.createRequest(OP.FRIEND_APPLY_UNHANDLED_COUNT);
    this.transport.send(frame);
    return promise.then((r) => r.data as number);
  }
}
