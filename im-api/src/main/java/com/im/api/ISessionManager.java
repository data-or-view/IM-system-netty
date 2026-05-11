package com.im.api;

import io.netty.channel.Channel;

import java.util.List;

/**
 * 会话管理器接口，负责 Channel 与用户会话的映射管理。
 *
 * 提供两个索引方向的查询：
 *   Channel → ConnectionSession（用于事件驱动，如 channelInactive）
 *   userId  → ConnectionSession（用于消息投递，如 DeliveryConsumer）
 *
 * 支持多端在线：同一 userId 可关联多个 Channel/session。
 * 参考 OpenIM 的多端登录策略：手机+PC+Web 同时在线。
 */
public interface ISessionManager {

    /**
     * 为 Channel 创建新会话。
     * 会话初始状态：unauthenticated。
     */
    IConnectionSession createSession(Channel channel);

    /**
     * 移除 Channel 对应的会话。
     * 如果频道关联了 userId，同时清理 userId→session 映射。
     *
     * @return 被移除的会话，如果不存在返回 null
     */
    IConnectionSession removeSession(Channel channel);

    /**
     * 通过 Channel 获取会话。
     */
    IConnectionSession getByChannel(Channel channel);

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
    IConnectionSession bindUser(Channel channel, String userId);

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
}
