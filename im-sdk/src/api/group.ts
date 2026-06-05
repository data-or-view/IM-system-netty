import {
  GroupJoinVerification,
  GroupType,
  type GroupApply,
  type GroupInfo,
  type GroupJoinVerificationValue,
  type GroupMember,
  type GroupTypeValue,
} from "../types.js";
import { type HttpAPI, requireHttp } from "./http-api.js";

const GROUP_TYPE_CODE: Record<GroupTypeValue, number> = {
  [GroupType.PRIVATE]: 0,
  [GroupType.PUBLIC]: 1,
};

const GROUP_JOIN_VERIFICATION_CODE: Record<GroupJoinVerificationValue, number> = {
  [GroupJoinVerification.DIRECT]: 0,
  [GroupJoinVerification.NEED_APPROVAL]: 1,
  [GroupJoinVerification.INVITE_ONLY]: 2,
  [GroupJoinVerification.FORBIDDEN]: 3,
};

/**
 * 群组模块 API。
 */
export class GroupAPI {
  constructor(private transport?: HttpAPI) {}

  /** 创建群组 */
  create(
    groupName: string,
    groupType: GroupTypeValue = GroupType.PRIVATE,
    memberIds?: string[],
    needVerification: GroupJoinVerificationValue = GroupJoinVerification.DIRECT,
  ): Promise<GroupInfo> {
    return requireHttp(this.transport).post<GroupInfo>("/api/group/create", {
      groupName,
      groupType: GROUP_TYPE_CODE[groupType],
      ...(memberIds ? { members: memberIds } : {}),
      needVerification: GROUP_JOIN_VERIFICATION_CODE[needVerification],
    });
  }

  /** 加入群组 */
  join(groupId: string, reqMsg?: string): Promise<void> {
    return requireHttp(this.transport).post("/api/group/join", {
      groupId,
      ...(reqMsg ? { reqMsg } : {}),
    }).then(() => undefined);
  }

  /** 退出群组 */
  quit(groupId: string): Promise<void> {
    return requireHttp(this.transport).post("/api/group/quit", { groupId }).then(() => undefined);
  }

  /** 踢出群成员（群主/管理员） */
  kick(groupId: string, userId: string): Promise<void> {
    return requireHttp(this.transport).post("/api/group/kick", {
      groupId,
      targetUserId: userId,
    }).then(() => undefined);
  }

  /** 解散群组 */
  disband(groupId: string): Promise<void> {
    return requireHttp(this.transport).post("/api/group/disband", { groupId }).then(() => undefined);
  }

  /** 更新群信息 */
  updateInfo(groupId: string, params: Record<string, unknown>): Promise<void> {
    return requireHttp(this.transport).post("/api/group/info/update", {
      groupId,
      ...params,
    }).then(() => undefined);
  }

  /** 获取群信息 */
  info(groupId: string): Promise<GroupInfo> {
    return requireHttp(this.transport).get<GroupInfo>("/api/group/info", { groupId });
  }

  /** 获取我加入的群组列表 */
  list(): Promise<GroupInfo[]> {
    return requireHttp(this.transport).get<{ groups?: GroupInfo[] } | GroupInfo[]>("/api/group/list")
      .then((data) => Array.isArray(data) ? data : data.groups ?? []);
  }

  /** 搜索群组 */
  search(keyword: string, limit = 20): Promise<GroupInfo[]> {
    return requireHttp(this.transport).get<{ groups?: GroupInfo[] }>("/api/group/search", {
      keyword,
      limit,
    }).then((data) => data.groups ?? []);
  }

  /** 获取群成员列表 */
  members(groupId: string): Promise<GroupMember[]> {
    return requireHttp(this.transport).get<{ members?: GroupMember[] }>("/api/group/members", { groupId })
      .then((data) => data.members ?? []);
  }

  /** 全员禁言 */
  muteAll(groupId: string, muted: boolean): Promise<void> {
    return requireHttp(this.transport).post("/api/group/mute/all", {
      groupId,
      mute: muted,
    }).then(() => undefined);
  }

  /** 获取我可审批的加群申请 */
  applyList(onlyPending = true): Promise<GroupApply[]> {
    return requireHttp(this.transport).get<{ applies?: GroupApply[] }>("/api/group/apply/list", {
      onlyPending,
    }).then((data) => data.applies ?? []);
  }

  /** 获取我可审批的未处理加群申请数量 */
  unhandledApplyCount(): Promise<number> {
    return requireHttp(this.transport).get<{ count?: number } | number>("/api/group/apply/unhandled/count")
      .then((data) => typeof data === "number" ? data : data.count ?? 0);
  }

  /** 审批加群申请 */
  approveApply(groupId: string, userId: string, agreed: boolean, handleMsg?: string): Promise<void> {
    return requireHttp(this.transport).post("/api/group/apply/approve", {
      groupId,
      userId,
      agreed,
      ...(handleMsg ? { handleMsg } : {}),
    }).then(() => undefined);
  }
}
