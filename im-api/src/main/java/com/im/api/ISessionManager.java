package com.im.api;

import java.util.List;

/**
 * 会话管理器接口，负责连接与用户会话的映射管理。
 *
 * 提供两个索引方向的查询：
 *   connectionId → ConnectionSession（用于事件驱动，如 channelInactive）
 *   userId  → ConnectionSession（用于消息投递，如 DeliveryConsumer）
 *
 * 支持多端在线：同一 userId 可关联多个 connection/session。
 * 参考 OpenIM 的多端登录策略：手机+PC+Web 同时在线。
 */
public interface ISessionManager {

    /**
     * 为连接创建新会话。
     * 会话初始状态：unauthenticated。
     */
    IConnectionSession createSession(ConnectionRef connection);

    /**
     * 移除连接对应的会话。
     * 如果频道关联了 userId，同时清理 userId→session 映射。
     *
     * @return 被移除的会话，如果不存在返回 null
     */
    IConnectionSession removeSession(String connectionId);

    /**
     * 通过 connectionId 获取会话。
     */
    IConnectionSession getByConnectionId(String connectionId);

    /**
     * 通过 userId 获取主会话。
     * 多端在线时返回第一个绑定该 userId 的 session。
     */
    IConnectionSession getByUserId(String userId);

    /**
     * 通过 userId 获取所有会话（多端在线）。
     * 返回当前在线端的所有 session，用于消息广播到所有端。
     */
    List<IConnectionSession> getSessionsByUserId(String userId);

    /**
     * 将 userId 绑定到已有会话。
     * 多端登录时，新 session 追加到该 userId 的 session 列表。
     *
     * @return 如果踢掉了旧端的 session 则返回（策略 1），否则返回 null
     */
    IConnectionSession bindUser(String connectionId, String userId, int platformId);

    default IConnectionSession bindUser(String connectionId, String userId) {
        return bindUser(connectionId, userId, PlatformID.DEFAULT);
    }

    /**
     * 扫描并关停空闲超时的未认证会话（登录超时）。
     */
    int scanIdleSessions(int idleSeconds);

    /**
     * 获取当前所有活跃会话。
     */
    List<IConnectionSession> allSessions();

    /**
     * 清除所有会话。
     */
    void clear();

    // ========================================
    //  强制登出
    // ========================================

    /**
     * 强制用户登出（全部端）。
     *
     * <p>关闭该用户的所有活跃连接，并使 token 失效。
     * 对应管理员后台的"踢用户下线"功能。</p>
     *
     * @param userId     目标用户
     */
    default void forceLogout(String userId) {
        throw new UnsupportedOperationException("forceLogout not implemented");
    }

    /**
     * 强制用户在指定平台登出。
     *
     * <p>仅关闭该用户在该平台的连接（例如只踢手机端，保留 PC 端）。
     * 对应 OpenIM 的 force_logout 指定 platformID。</p>
     *
     * @param userId     目标用户
     * @param platformId 要踢的平台（{@link PlatformID}），-1=全部
     */
    default void forceLogout(String userId, int platformId) {
        throw new UnsupportedOperationException("forceLogout(platform) not implemented");
    }

    /**
     * 强制指定 session 登出。
     *
     * @param userId     目标用户
     * @param platformId 目标平台
     * @param sessionId  目标 session
     */
    default void forceLogoutSession(String userId, int platformId, String sessionId) {
        throw new UnsupportedOperationException("forceLogoutSession not implemented");
    }

    // ========================================
    //  多端登录策略
    // ========================================

    /**
     * 获取用户当前生效的多端登录策略。
     *
     * @param userId 用户 ID
     * @return 该用户的多端登录策略（若未配置则返回系统默认策略）
     */
    default MultiLoginStrategy getMultiLoginStrategy(String userId) {
        return MultiLoginStrategy.ALLOW_MULTIPLE;
    }

    /**
     * 设置用户的多端登录策略（覆盖系统默认）。
     *
     * <p>可为不同用户设置不同的策略：
     *   普通用户 → ALLOW_MULTIPLE（手机+PC 同时在线）
     *   高安全用户 → KICK_OLD（只允许一端在线）</p>
     *
     * @param userId   用户 ID
     * @param strategy 多端登录策略
     */
    default void setMultiLoginStrategy(String userId, MultiLoginStrategy strategy) {
        throw new UnsupportedOperationException("setMultiLoginStrategy not implemented");
    }

    /**
     * 查询用户当前在线的端列表。
     *
     * <p>用于"我的设备"页面展示。</p>
     *
     * @param userId 用户 ID
     * @return 在线会话列表，含每个会话的平台 ID、IP、登录时间
     */
    default List<IConnectionSession> getOnlineSessions(String userId) {
        throw new UnsupportedOperationException("getOnlineSessions not implemented");
    }
}
