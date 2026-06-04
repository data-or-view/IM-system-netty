import { type GroupInfo, type GroupMember } from "../types.js";
import { type HttpAPI, requireHttp } from "./http-api.js";

/**
 * 群组模块 API。
 */
export class GroupAPI {
  constructor(private transport?: HttpAPI) {}

  /** 创建群组 */
  create(groupName: string, groupType?: number, memberIds?: string[]): Promise<GroupInfo> {
    return requireHttp(this.transport).post<GroupInfo>("/api/group/create", {
      groupName,
      ...(groupType !== undefined ? { groupType } : {}),
      ...(memberIds ? { members: memberIds } : {}),
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
}
