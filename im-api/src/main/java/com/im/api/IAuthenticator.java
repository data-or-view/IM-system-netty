package com.im.api;

import java.time.Duration;

/**
 * 认证/鉴权接口。
 *
 * 职责：
 *   ① 签发 token（登录成功时）
 *   ② 验证 token（每次请求时，由 AuthenticationInterceptor 调用）
 *   ③ 撤销 token（登出时）
 *   ④ 查询 appManagerLevel（由 AuthorizationInterceptor 调用）
 *
 * 当前实现：HmacTokenAuthenticator（HMAC-SHA256 自签）
 * 生产可替换：JwtAuthenticator（标准 JWT，对接认证中心）
 *
 * Token 结构（兼容 JWT 三段式）：
 *   base64url(header) + "." + base64url(payload) + "." + base64url(signature)
 *   payload: {"uid":"user123","exp":1700000000,"lvl":0}
 */
public interface IAuthenticator {

    /**
     * 为 userId 签发 token（默认 roleLevel=0 普通用户）。
     *
     * @param userId 用户 ID
     * @param ttl    token 有效期（如 Duration.ofDays(7)）
     * @return token 字符串
     */
    String issueToken(String userId, Duration ttl);

    /**
     * 为 userId 签发 token（含 appManagerLevel）。
     *
     * @param userId          用户 ID
     * @param ttl             token 有效期
     * @param appManagerLevel 用户管理权限等级（0=普通, 1=管理员, 2=超级管理员）
     * @return token 字符串
     */
    default String issueToken(String userId, Duration ttl, int appManagerLevel) {
        return issueToken(userId, ttl);
    }

    /**
     * 验证 token，返回 userId。
     *
     * @param token token 字符串
     * @return userId
     * @throws ImException 如果 token 无效/过期（UNAUTHORIZED）
     */
    String authenticate(String token);

    /**
     * 从 token 中提取 appManagerLevel。
     *
     * @param token token 字符串
     * @return appManagerLevel（0=普通用户, 1=管理员, 2=超级管理员）
     */
    default int getAppManagerLevel(String token) {
        return 0;
    }

    /**
     * 使 token 失效（登出时调用）。
     */
    default void revokeToken(String token) {
        // 无状态 JWT 需要黑名单机制，默认空实现
    }
}
