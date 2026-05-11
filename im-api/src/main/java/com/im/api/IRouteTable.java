package com.im.api;

import java.util.List;

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

    /**
     * 用户上线：注册 userId → nodeId 映射。
     * 同一用户可以在多个节点登录（多端），会新增一条记录。
     */
    void online(String userId, String nodeId);

    /**
     * 用户下线：移除该用户在该节点的映射。
     * 如果用户只在该节点登录，该用户从路由表中消失。
     */
    void offline(String userId, String nodeId);

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
     * 判断用户是否在线（任意节点）。
     */
    default boolean isOnline(String userId) {
        return lookup(userId) != null;
    }
}
