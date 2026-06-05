import { type FriendInfo, type FriendApply } from "../types.js";
import { type HttpAPI, requireHttp } from "./http-api.js";

/**
 * 好友模块 API。
 */
export class FriendAPI {
  constructor(private transport?: HttpAPI) {}

  /** 获取好友列表 */
  list(): Promise<FriendInfo[]> {
    return requireHttp(this.transport).get<{ friends?: FriendInfo[] }>("/api/friend/list")
      .then((data) => data.friends ?? []);
  }

  /** 搜索用户（添加好友前搜索） */
  search(keyword: string, limit = 20): Promise<FriendInfo[]> {
    return requireHttp(this.transport).get<{ users?: FriendInfo[] } | FriendInfo[]>("/api/user/search", {
      keyword,
      limit,
    }).then((data) => Array.isArray(data) ? data : data.users ?? []);
  }

  /** 申请加好友 */
  apply(targetUserId: string, reqMsg?: string): Promise<void> {
    return requireHttp(this.transport).post("/api/friend/apply", {
      toUserId: targetUserId,
      ...(reqMsg ? { reqMsg } : {}),
    }).then(() => undefined);
  }

  /** 审批好友申请 */
  approve(fromUserId: string, agreed: boolean): Promise<void> {
    return requireHttp(this.transport).post("/api/friend/approve", {
      fromUserId,
      agreed,
    }).then(() => undefined);
  }

  /** 删除好友 */
  remove(friendUserId: string): Promise<void> {
    return requireHttp(this.transport).post("/api/friend/remove", { friendUserId }).then(() => undefined);
  }

  /** 拉黑 */
  black(targetUserId: string): Promise<void> {
    return requireHttp(this.transport).post("/api/friend/black", { blockedUserId: targetUserId }).then(() => undefined);
  }

  /** 取消拉黑 */
  unblack(targetUserId: string): Promise<void> {
    return requireHttp(this.transport).post("/api/friend/unblack", { blockedUserId: targetUserId }).then(() => undefined);
  }

  /** 黑名单列表 */
  blacklist(): Promise<FriendInfo[]> {
    return requireHttp(this.transport).get<{ blacklist?: FriendInfo[] }>("/api/friend/blacklist")
      .then((data) => data.blacklist ?? []);
  }

  /** 已发送的好友申请列表 */
  sentApplyList(): Promise<FriendApply[]> {
    return requireHttp(this.transport).get<{ applies?: FriendApply[] }>("/api/friend/apply/sent")
      .then((data) => data.applies ?? []);
  }

  /** 收到的好友申请列表 */
  receivedApplyList(onlyPending = true): Promise<FriendApply[]> {
    return requireHttp(this.transport).get<{ applies?: FriendApply[] }>("/api/friend/apply/received", {
      onlyPending,
    }).then((data) => data.applies ?? []);
  }

  /** 未处理的好友申请数量 */
  unhandledApplyCount(): Promise<number> {
    return requireHttp(this.transport).get<{ count?: number } | number>("/api/friend/apply/unhandled/count")
      .then((data) => typeof data === "number" ? data : data.count ?? 0);
  }
}
