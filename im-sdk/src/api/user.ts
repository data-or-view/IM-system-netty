import { OP, type UserInfo, type WSResponse } from "../types.js";
import type { WsTransport } from "../transport/ws.js";
import { type HttpAPI, requireHttp } from "./http-api.js";

/**
 * 用户模块 API。
 *
 * 注册/资料查询/搜索/资料更新属于资源型 API，走 HTTP；
 * login 暂时保留 WS，因为后端登录同时承担连接绑定和上线语义。
 */
export class UserAPI {
  constructor(private wsTransport: WsTransport, private httpTransport?: HttpAPI) {}

  /** 注册新用户 */
  register(userId: string, password?: string, nickname?: string, faceUrl?: string): Promise<WSResponse> {
    return requireHttp(this.httpTransport).post<unknown>("/api/user/register", {
      userId,
      ...(password ? { password } : {}),
      ...(nickname ? { nickname } : {}),
      ...(faceUrl ? { faceUrl } : {}),
    }).then((data) => ({ op: `${OP.USER_REGISTER}_ack`, seq: 0, code: 0, data }));
  }

  /** 登录 */
  login(userId: string, password?: string): Promise<WSResponse> {
    return this.wsTransport.request(OP.LOGIN, {
      userId,
      ...(password ? { password } : {}),
    });
  }

  /** 获取用户信息 */
  info(userId: string): Promise<UserInfo> {
    return requireHttp(this.httpTransport).get<UserInfo>("/api/user/info", { userId });
  }

  /** 搜索用户 */
  search(keyword: string, limit = 20): Promise<UserInfo[]> {
    return requireHttp(this.httpTransport).get<{ users?: UserInfo[] } | UserInfo[]>("/api/user/search", {
      keyword,
      limit,
    }).then((data) => Array.isArray(data) ? data : data.users ?? []);
  }

  /** 更新用户信息 */
  update(params: Record<string, unknown>): Promise<void> {
    return requireHttp(this.httpTransport).post("/api/user/update", params).then(() => undefined);
  }
}
