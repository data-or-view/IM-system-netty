import { OP, type UserInfo, type WSResponse } from "../types.js";
import type { WsTransport } from "../transport/ws.js";

/**
 * 用户模块 API。
 *
 * 每个方法自动注入 Authorization token，返回 Promise。
 */
export class UserAPI {
  constructor(private transport: WsTransport) {}

  /** 注册新用户 */
  register(userId: string, password?: string, nickname?: string, faceUrl?: string): Promise<WSResponse> {
    const { frame, promise } = this.transport.requestManager.createRequest(OP.REGISTER, {
      userId,
      ...(password ? { password } : {}),
      ...(nickname ? { nickname } : {}),
      ...(faceUrl ? { faceUrl } : {}),
    });
    this.transport.send(frame);
    return promise;
  }

  /** 登录 */
  login(userId: string, password?: string): Promise<WSResponse> {
    const { frame, promise } = this.transport.requestManager.createRequest(OP.LOGIN, {
      userId,
      ...(password ? { password } : {}),
    });
    this.transport.send(frame);
    return promise;
  }

  /** 获取用户信息 */
  info(userId: string): Promise<UserInfo> {
    const { frame, promise } = this.transport.requestManager.createRequest(OP.USER_INFO, {
      userId,
    });
    this.transport.send(frame);
    return promise.then((r) => r.data as UserInfo);
  }

  /** 搜索用户 */
  search(keyword: string, limit = 20): Promise<UserInfo[]> {
    const { frame, promise } = this.transport.requestManager.createRequest(OP.USER_SEARCH, {
      keyword,
      limit,
    });
    this.transport.send(frame);
    return promise.then((r) => r.data as UserInfo[]);
  }

  /** 更新用户信息 */
  update(params: Record<string, unknown>): Promise<void> {
    const { frame, promise } = this.transport.requestManager.createRequest(OP.USER_UPDATE, params);
    this.transport.send(frame);
    return promise.then(() => undefined);
  }
}
