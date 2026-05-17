# 集群部署约束

本项目一定是多节点集群部署的，写代码时需考虑以下约束：

## 原则

- **不能使用本地内存（Local 实现）存储任何跨节点共享状态**。所有业务数据（会话、消息、路由、在线状态等）必须写入 Redis 或 MySQL。
- Local 实现仅作为**单机开发/测试兜底**，不可用于任何生产路径。

## 具体要求

| 领域 | 要求 |
|------|------|
| 会话管理 | Conversation 数据必须存 Redis Hash 或 MySQL，禁止 LocalConversationManager |
| 路由表 | 用户在线状态必须走 Redis RouteTable，禁止 LocalRouteTable |
| 消息序号 | 用 Redis INCR 保证多节点递增（RedisSequenceManager） |
| 消息投递 | 用户连在不同节点时，消息需通过 ClusterMessageBus 转发 |
| 消息持久化 | 写 MySQL（或后续的 Redis Streams → MySQL 异步管道） |
| 节点发现 | 节点启动时注册到 Redis，心跳保活（RedisNodeDiscovery） |
| 幂等性 | 消息持久化、会话更新等操作需幂等（允许重复执行） |

## 新增组件检查清单

添加任何新组件时问自己：
1. 这个状态多个节点需要看到吗？→ 存 Redis/DB
2. 这个操作多个节点同时执行会冲突吗？→ 加分布式锁或用 Redis 原子操作
3. 如果某个节点挂了，数据会丢吗？→ 考虑持久化或副本
