---
name: cluster-deployment
description: 集群部署约束 — 本项目为多节点部署，禁止 Local 实现存跨节点共享状态
---

# 集群部署约束

本项目**一定**是多节点集群部署，写代码时必须考虑集群环境。

## 硬性规则

1. **禁止用本地内存存业务状态** — 会话、路由、在线状态、未读数等跨节点可见的数据必须写 Redis 或 MySQL
2. **Local / 内存实现只能用于单机开发/测试**，不准出现在生产路径
3. **用 Redis 原子操作（INCR / HINCRBY / Lua）替代本地锁** — 多节点同时操作同一资源时不会冲突
4. **幂等设计** — 消息持久化、会话更新等操作允许重复执行，不能产生脏数据

## 已有组件哪些是集群安全的

| 组件 | 存储 | 集群安全 |
|------|------|----------|
| RedisRouteTable | Redis | ✅ |
| RedisSequenceManager | Redis | ✅ |
| RedisNodeDiscovery | Redis | ✅ |
| RedisClusterMessageBus | Redis Pub/Sub | ✅ |
| RedisConversationManager | Redis Hash+ZSet | ✅ |
| RedisMessageQueue | Redis Streams | ✅ |
| DbConversationManager | MySQL | ✅ |
| DbMessageStore | MySQL | ✅ |
| DbGroupManager | MySQL | ✅ |
| LocalConversationManager | 内存 | ❌ 仅测试用 |
| LocalRouteTable | 内存 | ❌ 仅测试用 |

## 使用场景

设计新功能、加新 Manager / Store / UseCase 时，先问自己：
- 这个状态要不要跨节点可见？
- 要不要持久化不丢？
- 多节点同时写会怎样？

如果以上任意答案为"是"，就不要用内存实现。

## 对比参考

见 `openim-reference` skill — OpenIM 是微服务架构，天然集群部署。
