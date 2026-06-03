package com.im.api;

/**
 * 连接会话接口，封装一条传输无关连接及其关联的用户身份。
 *
 * 参考 RocketMQ 的 ClientChannelInfo：
 *   - ConnectionRef connection ← 传输连接引用
 *   - String clientId       ← 对应 userId
 *   - long lastUpdateTimestamp ← 对应 lastActiveTime
 *
 * 额外增加了 authenticated 状态和 sessionId，用于 IM 场景。
 */
public interface IConnectionSession {

    /**
     * 全局唯一会话 ID。
     */
    String getSessionId();

    /**
     * 关联的用户 ID。登录成功前返回 null。
     */
    String getUserId();

    /**
     * 底层连接引用。
     */
    ConnectionRef getConnection();

    /**
     * 远程地址。
     */
    String getRemoteAddress();

    /**
     * 是否已验证（登录成功）。
     */
    boolean isAuthenticated();

    /**
     * 标记为已验证，绑定 userId。
     */
    void authenticate(String userId);

    /**
     * 标记为已验证，绑定 userId + platformId。
     */
    default void authenticate(String userId, int platformId) {
        authenticate(userId);
    }

    /**
     * 平台 ID，对应 PlatformID 常量。
     * 未认证明确定义平台时返回 {@link PlatformID#DEFAULT}。
     */
    default int getPlatformId() {
        return PlatformID.DEFAULT;
    }

    /**
     * 上次活跃时间戳（毫秒）。
     * 每次收到消息时更新，用于空闲检测。
     */
    long getLastActiveTime();

    /**
     * 刷新活跃时间。
     */
    void touch();

    /**
     * 会话创建时间。
     */
    long getCreationTime();
}
