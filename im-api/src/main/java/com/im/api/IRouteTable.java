package com.im.api;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 用户路由表（userId → 节点）。
 *
 * 当用户登录 A 节点，对端用户在 B 节点时，A 通过路由表知道"将消息转发到 B"。
 *
 * 结构（参考 RocketMQ NameServer 的 topicQueueTable → brokerAddrTable 双层映射）：
 *   userId ──→ 在线节点列表（多端登录）──→ 节点地址
 *
 * 实现选择：
 *   ┌──────────────┬─────────────────────────────────┐
 *   │ 部署模式     │ 实现                            │
 *   ├──────────────┼─────────────────────────────────┤
 *   │ 单机         │ LocalRouteTable (本地 HashMap)  │
 *   │ 集群(小)     │ RedisRouteTable                 │
 *   │ 集群(大)     │ RedisRouteTable / EtcdRouteTable│
 *   └──────────────┴─────────────────────────────────┘
 */
public interface IRouteTable {

    // ========== 节点路由 ==========

    /**
     * 用户上线：注册 userId → nodeId 映射。
     * 同一用户可以在多个节点登录（多端），会新增一条记录。
     */
    default void online(String userId, String nodeId) {
        online(userId, nodeId, PlatformID.DEFAULT, "default");
    }

    /**
     * 用户指定端上线。
     * platformId + sessionId 用于区分同一用户在多个节点/多个端的路由。
     */
    void online(String userId, String nodeId, int platformId, String sessionId);

    /**
     * 用户下线：移除该用户在该节点的映射。
     * 如果用户只在该节点登录，该用户从路由表中消失。
     */
    default void offline(String userId, String nodeId) {
        offline(userId, nodeId, PlatformID.DEFAULT, "default");
    }

    /**
     * 用户指定端下线，只移除当前 session 的路由。
     */
    void offline(String userId, String nodeId, int platformId, String sessionId);

    /**
     * Removes the concrete route binding observed by a caller.
     *
     * <p>Cluster implementations must treat this as a conditional removal:
     * a binding replaced after it was observed must remain online.</p>
     */
    default void offline(RouteBinding binding) {
        offlineIfCurrent(binding);
    }

    /**
     * Atomically removes an observed binding only while its complete identity is current.
     *
     * @return {@code true} only when that exact binding was removed
     */
    default boolean offlineIfCurrent(RouteBinding binding) {
        return false;
    }

    /**
     * 查找用户所在节点（第一条匹配记录）。
     * 如果用户在本节点登录，返回 local RouteNode。
     */
    RouteNode lookup(String userId);

    /**
     * 查找用户的所有登录节点（多端登录）。
     * 例如：手机在 nodeA，电脑在 nodeB → 返回 2 条。
     */
    List<RouteNode> lookupAll(String userId);

    /**
     * 查找用户的所有在线 session 路由。
     */
    default List<RouteBinding> lookupAllBindings(String userId) {
        return lookupAll(userId).stream()
                .map(route -> new RouteBinding(userId, route.getNodeId(), PlatformID.DEFAULT, "default", 0))
                .toList();
    }

    /**
     * 判断用户是否在线（任意节点）。
     */
    default boolean isOnline(String userId) {
        return lookup(userId) != null;
    }

    // ========== 在线状态（Platform 级别） ==========

    /**
     * 用户指定平台上线。
     * 对应场景：用户登录成功后，标记该 platform 在线。
     */
    void setOnline(String userId, int platformId);

    /**
     * 用户指定平台下线。
     * 对应场景：用户断连或登出，移除该 platform。
     */
    void setOffline(String userId, int platformId);

    /**
     * 查询用户当前在线的所有平台 ID 列表。
     * 空列表表示用户全平台离线。
     */
    List<Integer> getOnlinePlatforms(String userId);

    /**
     * 批量查询多个用户的在线平台。
     */
    default Map<String, List<Integer>> batchGetOnlinePlatforms(List<String> userIds) {
        return Collections.emptyMap();
    }

    /**
     * 续期用户指定平台的在线状态（心跳保活）。
     * 延长该 platform 的过期时间。
     */
    void renewOnline(String userId, int platformId);

    /**
     * 续期用户指定 session 的在线状态和路由状态（心跳保活）。
     *
     * <p>集群部署时，同一用户同一平台可能存在新旧 session 的短暂并存窗口，
     * 因此生产实现应优先刷新 platformId + sessionId 对应的路由字段。</p>
     */
    default void renewOnline(String userId, int platformId, String sessionId) {
        renewOnline(userId, platformId);
    }

    /**
     * 清理指定节点遗留的路由。
     *
     * <p>生产集群中节点异常下线时，节点本身无法逐个执行用户 offline。
     * Redis/MySQL 实现应维护 nodeId 到路由字段的反向索引，并在节点过期或注销时删除该节点残留路由。</p>
     *
     * @return 删除的路由绑定数量
     */
    default int cleanupNodeRoutes(String nodeId) {
        return 0;
    }

    /** Cleans routes owned by one exact process incarnation of a node. */
    default int cleanupNodeRoutes(String nodeId, String nodeIncarnation) {
        return 0;
    }
}
