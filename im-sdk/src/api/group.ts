import { OP, type GroupInfo, type GroupMember, type WSResponse } from "../types.js";
import type { WsTransport } from "../transport/ws.js";

/**
 * 群组模块 API。
 */
export class GroupAPI {
  constructor(private transport: WsTransport) {}

  /** 创建群组 */
  create(groupName: string, groupType?: number, memberIds?: string[]): Promise<GroupInfo> {
    const { frame, promise } = this.transport.requestManager.createRequest(OP.GROUP_CREATE, {
      groupName,
      ...(groupType !== undefined ? { groupType } : {}),
      ...(memberIds ? { members: memberIds.join(",") } : {}),
    });
    this.transport.send(frame);
    return promise.then((r) => r.data as GroupInfo);
  }

  /** 加入群组 */
  join(groupId: string, reqMsg?: string): Promise<void> {
    const { frame, promise } = this.transport.requestManager.createRequest(OP.GROUP_JOIN, {
      groupId,
      ...(reqMsg ? { reqMsg } : {}),
    });
    this.transport.send(frame);
    return promise.then(() => undefined);
  }

  /** 退出群组 */
  quit(groupId: string): Promise<void> {
    const { frame, promise } = this.transport.requestManager.createRequest(OP.GROUP_QUIT, { groupId });
    this.transport.send(frame);
    return promise.then(() => undefined);
  }

  /** 踢出群成员（群主/管理员） */
  kick(groupId: string, userId: string): Promise<void> {
    const { frame, promise } = this.transport.requestManager.createRequest(OP.GROUP_KICK, {
      groupId,
      userId,
    });
    this.transport.send(frame);
    return promise.then(() => undefined);
  }

  /** 解散群组 */
  disband(groupId: string): Promise<void> {
    const { frame, promise } = this.transport.requestManager.createRequest(OP.GROUP_DISBAND, { groupId });
    this.transport.send(frame);
    return promise.then(() => undefined);
  }

  /** 更新群信息 */
  updateInfo(groupId: string, params: Record<string, unknown>): Promise<void> {
    const { frame, promise } = this.transport.requestManager.createRequest(OP.GROUP_INFO_UPDATE, {
      groupId,
      ...params,
    });
    this.transport.send(frame);
    return promise.then(() => undefined);
  }

  /** 获取群信息 */
  info(groupId: string): Promise<GroupInfo> {
    const { frame, promise } = this.transport.requestManager.createRequest(OP.GROUP_INFO, { groupId });
    this.transport.send(frame);
    return promise.then((r) => r.data as GroupInfo);
  }

  /** 搜索群组 */
  search(keyword: string, limit = 20): Promise<GroupInfo[]> {
    const { frame, promise } = this.transport.requestManager.createRequest(OP.GROUP_SEARCH, {
      keyword,
      limit,
    });
    this.transport.send(frame);
    return promise.then((r) => {
      const data = r.data as { groups?: GroupInfo[] } | null;
      return data?.groups ?? [];
    });
  }

  /** 获取群成员列表 */
  members(groupId: string): Promise<GroupMember[]> {
    const { frame, promise } = this.transport.requestManager.createRequest(OP.GROUP_MEMBERS, { groupId });
    this.transport.send(frame);
    return promise.then((r) => {
      const data = r.data as { members?: GroupMember[] } | null;
      return data?.members ?? [];
    });
  }

  /** 全员禁言 */
  muteAll(groupId: string, muted: boolean): Promise<void> {
    const { frame, promise } = this.transport.requestManager.createRequest(OP.GROUP_MUTE_ALL, {
      groupId,
      muted: String(muted),
    });
    this.transport.send(frame);
    return promise.then(() => undefined);
  }
}
