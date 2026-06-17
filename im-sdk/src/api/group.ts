import {
  GroupJoinVerification,
  GroupType,
  type GroupApply,
  type GroupCallJoinResult,
  type GroupCallSession,
  type GroupInfo,
  type GroupJoinResponse,
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
  join(groupId: string, reqMsg?: string): Promise<GroupJoinResponse> {
    return requireHttp(this.transport).post<GroupJoinResponse>("/api/group/join", {
      groupId,
      ...(reqMsg ? { reqMsg } : {}),
    }).then((data) => ({
      status: data.result ?? data.status,
      ...(data.result ? { result: data.result } : {}),
    }));
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
    return requireHttp(this.transport).get<{ groups: GroupInfo[] }>("/api/group/list")
      .then((data) => data.groups);
  }

  /** 搜索群组 */
  search(keyword: string, limit = 20): Promise<GroupInfo[]> {
    return requireHttp(this.transport).get<{ groups: GroupInfo[] }>("/api/group/search", {
      keyword,
      limit,
    }).then((data) => data.groups);
  }

  /** 获取群成员列表 */
  members(groupId: string): Promise<GroupMember[]> {
    return requireHttp(this.transport).get<{ members: GroupMember[] }>("/api/group/members", { groupId })
      .then((data) => data.members);
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
    return requireHttp(this.transport).get<{ applies: GroupApply[] }>("/api/group/apply/list", {
      onlyPending,
    }).then((data) => data.applies);
  }

  /** 获取我可审批的未处理加群申请数量 */
  unhandledApplyCount(): Promise<number> {
    return requireHttp(this.transport).get<{ count: number }>("/api/group/apply/unhandled/count")
      .then((data) => data.count);
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

  /** 发起群语音/视频。 */
  startCall(groupId: string, callType: "voice" | "video" = "video"): Promise<GroupCallSession> {
    return requireHttp(this.transport).post<GroupCallSession>("/api/group/call/start", {
      groupId,
      callType,
    });
  }

  /** 加入当前群语音/视频，并获取 LiveKit token。 */
  joinCall(groupId: string): Promise<GroupCallJoinResult> {
    return requireHttp(this.transport).post<GroupCallJoinResult>("/api/group/call/join", { groupId });
  }

  /** 离开当前群语音/视频。 */
  leaveCall(groupId: string): Promise<GroupCallSession> {
    return requireHttp(this.transport).post<GroupCallSession>("/api/group/call/leave", { groupId });
  }

  /** 结束当前群语音/视频。 */
  endCall(groupId: string): Promise<GroupCallSession> {
    return requireHttp(this.transport).post<GroupCallSession>("/api/group/call/end", { groupId });
  }

  /** 查询当前群是否有正在进行的语音/视频。 */
  activeCall(groupId: string): Promise<GroupCallSession> {
    return requireHttp(this.transport).get<GroupCallSession>("/api/group/call/active", { groupId });
  }
}
