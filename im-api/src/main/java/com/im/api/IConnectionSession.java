package com.im.api;

import io.netty.channel.Channel;

import java.net.SocketAddress;

/**
 * 连接会话接口，封装一条 Channel 及其关联的用户身份。
 *
 * 参考 RocketMQ 的 ClientChannelInfo：
 *   - Channel channel       ← 对应 Netty Channel
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
     * 底层 Netty Channel。
     */
    Channel getChannel();

    /**
     * 远程地址。
     */
    SocketAddress getRemoteAddress();

    /**
     * 是否已验证（登录成功）。
     */
    boolean isAuthenticated();

    /**
     * 标记为已验证。
     */
    void authenticate(String userId);

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
