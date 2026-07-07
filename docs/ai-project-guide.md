# AI Project Guide

这份文档是给 AI 和新开发者的快读入口。目标是先建立正确的项目地图，再去改代码。

## 一句话

这是一个集群优先的全栈即时通讯系统：Java 21 + Netty 后端、React/Vite 前端、TypeScript SDK、多用户场景测试。生产路径要求 Redis 和 MySQL，不能用本地内存保存跨节点共享状态。

## 先读顺序

1. `AGENTS.md`: AI 写代码必须遵守的集群约束。
2. `README.md`: 项目结构和启动命令。
3. `ARCHITECTURE.md`: 后端装配、消息链路和数据模型。
4. `im-server/src/main/java/com/im/bootstrap/ServerComponentsFactory.java`: 生产组合根。
5. `im-api/src/main/java/com/im/api/Operation.java`: 协议单点真理。
6. `bin/restart-backend.sh` 和 `bin/start-cluster.sh`: 本地运行入口。

## 模块地图

| 模块 | 用途 | 常看文件 |
|------|------|----------|
| `im-api` | Java 接口、DTO、协议枚举、错误码 | `Operation.java`, `Message.java`, `ConversationIds.java` |
| `im-server` | 服务端启动、Netty、handler、use case、Redis/MySQL/RocketMQ/MinIO/LiveKit 实现 | `Main.java`, `ServerRuntime.java`, `DispatcherFactory.java`, `ServerComponentsFactory.java` |
| `im-infrastructure` | 配置、缓存、序列化、存储、幂等、消息中间件 | `im-infrastructure-config`, `im-infrastructure-idempotency`, `im-infrastructure-message-rocketmq` |
| `im-sdk` | 浏览器/Node 可用的 TS SDK | `src/index.ts`, `src/transport/ws.ts`, `src/transport/http.ts`, `src/api/*` |
| `im-web` | React 聊天工作台 | `src/App.tsx`, `src/pages/ChatLayout.tsx`, `src/store/*`, `src/sdk/im-sdk.ts` |
| `im-scenario-tests` | 多用户真实协议测试 | `scenarios/cluster-ha.ts`, `src/scenario-user.ts` |
| `bin` | 运行脚本 | `restart-backend.sh`, `start-cluster.sh`, `stop-cluster.sh` |
| `config` | 部署配置模板 | `application.yml`, `application-macbook-dev.yml`, `livekit/livekit.yaml` |

## 部署架构

```text
Browser
  |
  | im-web uses im-sdk
  v
Netty node N
  |
  |-- WebSocket /ws
  |     login, register, heartbeat, chat.send, chat.send.group, server push
  |
  |-- HTTP /api/*
        user, friend, group, conversation, message pull/search/sync, file, system, group call

Netty node N
  -> ApiDispatcher
  -> Handler / UseCase
  -> Redis: route, online, node discovery, sequence, cache, cluster bus
  -> MySQL: users, friends, groups, conversations, messages, idempotency, DLQ, files, system messages
  -> Redis Streams or RocketMQ: persist + deliver topics
  -> MinIO: object storage
  -> LiveKit: RTC room and token provider
```

本地集群脚本启动两个后端节点，它们共享同一套 Redis、MySQL、MQ 和 MinIO。

## 启动脚本

### 单节点开发后端

```bash
bin/restart-backend.sh
```

默认行为：

| 项 | 默认值 |
|----|--------|
| 环境 | `macbook-dev` |
| 节点 ID | `macbook-dev` |
| WebSocket | `8083` |
| HTTP | `8084` |
| 日志 | `logs/backend.log` |
| PID | `bin/pids/backend.pid` |
| 构建 | 默认先执行 `mvn -pl im-api,im-server -am package -DskipTests` |

常用参数：

```bash
bin/restart-backend.sh --no-build
bin/restart-backend.sh --foreground --no-build
bin/restart-backend.sh --ws-port 18081 --http-port 18084
```

### 本地双节点集群

```bash
bin/start-cluster.sh
```

默认节点：

| 节点 | WS | HTTP | 日志 |
|------|----|------|------|
| `node-1` | `8081` | `8088` | `logs/node-1.log` |
| `node-2` | `8084` | `8089` | `logs/node-2.log` |

脚本会检查端口、jar、Redis 可达性，并等待日志出现 `Server ready`。`node-2` 会追加 `-Dim.db.schema=none`，避免重复初始化 schema。

停止：

```bash
bin/stop-cluster.sh
```

### 前端

```bash
cd im-web
pnpm dev
```

默认：

```text
dev server: http://127.0.0.1:39073
WS:  ws://127.0.0.1:8083/ws
HTTP: http://127.0.0.1:8084
```

覆盖后端地址：

```bash
VITE_WS_URL=ws://127.0.0.1:8081/ws \
VITE_HTTP_URL=http://127.0.0.1:8088 \
pnpm --dir im-web dev
```

## 配置事实

服务端入口 `Main.loadConfig()` 只自动注册 classpath 配置：

```text
classpath:application.yml
classpath:application-{im.env}.yml
```

根目录 `config/` 是部署模板，不会被 `Main.loadConfig()` 自动读取。

当前代码优先级：

```text
IM_* 环境变量
  > -Dim.* 系统属性
  > classpath:application-{env}.yml
  > classpath:application.yml
  > application.properties
```

环境变量会把下划线转成点号，例如 `IM_REDIS_HOST` 变成 `im.redis.host`。

重要配置：

| 配置 | 说明 |
|------|------|
| `im.env` / `IM_ENV` | 选择 `application-{env}.yml`。本机常用 `macbook-dev`。 |
| `im.redis.host` | 生产组合根必填；为空会启动失败。 |
| `im.db.enabled` | 生产组合根必须为 `true`。 |
| `im.db.schema` | `none`、`auto`、`rebuild`。集群多节点只让一个节点建表。 |
| `im.mq.type` | `redis` / `redis-streams` / `rocketmq`。 |
| `im.rocketmq.*` | RocketMQ name server、producer group、consumer group、topic prefix。 |
| `im.minio.*` | 文件存储。 |
| `im.call.*` | LiveKit 通话配置。多机部署时 `sfu-endpoint` 不能是 localhost。 |
| `im.http.cors.allowed-origins` | 前端 dev server 默认 `39073`。 |

## 后端关键类

| 类 | 作用 |
|----|------|
| `Main` | 加载配置并启动 `IMServer`。 |
| `IMServer` | 生命周期外壳。 |
| `ServerComponentsFactory` | 生产组合根，决定 Redis/MySQL/MQ/MinIO/LiveKit 实现。 |
| `ServerRuntime` | 控制启动和停止顺序。 |
| `TransportServer` | 管理 Netty EventLoop、WS/HTTP Channel、空闲连接扫描。 |
| `DispatcherFactory` | 注册拦截器和所有 handler。 |
| `ApiDispatcher` | 统一请求分发和异常响应。 |
| `RedisRouteTable` | 用户路由、在线状态、节点反向索引。 |
| `RedisClusterMessageBus` | Redis Pub/Sub 跨节点转发。 |
| `RedisNodeDiscovery` | Redis 节点注册和心跳。 |
| `RedisSequenceManager` | Redis INCR 消息序号。 |
| `SendMessageUseCase` | 消息发送主流程。 |
| `PersistenceConsumer` | 消费 `persist`，写消息和会话。 |
| `DeliveryConsumer` | 消费 `deliver`，本地推送或跨节点转发。 |
| `BusinessMessageDlqCompensator` | 补偿失败的 MQ 业务消息。 |

## WebSocket 与 HTTP 边界

`Operation.java` 是协议单点真理。新增接口必须先改这里。

WS-only：

```text
login
register
heartbeat
chat.send
chat.send.group
```

HTTP categories：

```text
/api/user/*
/api/friend/*
/api/group/*
/api/conversation/*
/api/msg/*
/api/file/*
/api/system/*
/api/admin/system/*
```

服务端推送通过 WebSocket 返回给 SDK，SDK 再转为事件：

```text
message
friendRequest
groupApply
systemMessage
messageRevoked
connectionStateChanged
tokenChanged
error
```

## 消息链路

单聊和群聊都走 `ChatHandler`：

```text
ChatHandler
  -> ContentParser
  -> SendMessageUseCase
     -> require clientMsgId
     -> send policy
     -> webhook beforeSend
     -> RedisSequenceManager
     -> publish persist
     -> publish deliver
     -> webhook afterSend
```

`clientMsgId` 是发送幂等关键字段，必须是 8 到 64 位，允许字母、数字、点、下划线、冒号和横线。

`persist` 发布失败是硬错误。`deliver` 发布失败会返回 `RECEIVED_PENDING_DELIVERY`，并写入业务失败表等待补偿。

## 集群投递

```text
DeliveryConsumer
  -> routeTable.lookupAllBindings(targetUser)
  -> same node: sessionManager.getSessionsByUserId() -> Channel.write()
  -> remote node: clusterMessageBus.sendToNode()
  -> remote node ClusterDeliveryHandler -> local Channel.write()
```

路由绑定按 `platformId + sessionId` 精确投递，一个用户多端、多标签页可以同时在线。

## Redis Key 速查

| Key | 说明 |
|-----|------|
| `route:{userId}` | 用户到节点/session 的路由 Hash。 |
| `online:{userId}` | 用户平台在线状态 ZSet。 |
| `route_node:{nodeId}` | 节点反向路由索引。 |
| `im:node:{nodeId}` | 节点信息，30s TTL。 |
| `im:nodes:alive` | 活跃节点集合。 |
| `im:node:{nodeId}:msgs` | 节点专属 Pub/Sub 频道。 |
| `im:bus:broadcast` | 广播频道。 |
| `im:seq:{conversationId}` | 会话序号。 |
| `im:mq:stream:{topic}` | Redis Streams topic。 |
| `im:mq:group:{topic}` | Redis Streams consumer group。 |

## MySQL 表速查

主要表在 `im-server/src/main/resources/db/schema.sql`：

```text
im_users, im_friends, im_friend_requests, im_blacklist, im_refresh_tokens
im_groups, im_group_members, im_group_requests
im_conversations, im_messages, im_message_read_states, im_message_visibility
im_idempotency_records, im_message_send_failures
im_objects
im_sync_versions, im_sync_changes
im_system_channels, im_system_messages, im_system_message_inbox
```

## 前端事实

`im-web` 是实际聊天工作台，不是 Vite 模板。

关键能力：

- 登录、注册、token 本地存储和登录态校验。
- `/chat` 工作台路由。
- 会话列表、消息区、好友/群组/系统消息刷新。
- SDK 事件合并到前端 store。
- LiveKit 通话弹窗和 provider。

关键文件：

```text
src/config/routes.ts
src/config/app-behavior.ts
src/lib/app-errors.ts
src/sdk/im-sdk.ts
src/store/store.tsx
src/store/store-types.ts
src/store/store-reducer.ts
src/store/store-helpers.ts
src/store/useStoreSdkEvents.ts
src/store/domain.ts
src/components/GlobalErrorHandler.tsx
src/pages/ChatLayout.tsx
src/components/ChatArea.tsx
src/components/chat/*
src/components/Sidebar.tsx
src/components/sidebar/*
src/components/call/*
src/pages/GroupInfoPage.tsx
src/pages/group-info/*
```

前端工程约束：

- 页面路径、redirect 目标和用户/群资料跳转必须通过 `src/config/routes.ts`，不要在组件里散落 `/chat`、`/login` 字符串。
- 缓存 TTL、刷新 debounce、搜索/分页数量等非视觉行为数值放在 `src/config/app-behavior.ts`。
- 登录态校验只在明确 token 失效时 `logout()`；连接失败、超时、5xx 等临时错误要保留登录状态。
- 全局错误入口是 `src/components/GlobalErrorHandler.tsx` 和 `src/lib/app-errors.ts`，会监听 SDK `error`、`window.error`、`unhandledrejection` 和 `im:app-error`。
- 大型前端文件保持编排层职责：`store/store.tsx` 只做 Provider 编排，reducer/types/helpers/SDK 事件桥接在相邻 store 模块；`Sidebar.tsx` 的列表和 rail 在 `components/sidebar/*`；`CallProvider.tsx` 的配置、错误、注意力和 LiveKit room 逻辑在 `components/call/*`；`GroupInfoPage.tsx` 的管理 hook、成员列表和编辑弹窗在 `pages/group-info/*`。
- 改前端工程约束后运行 `pnpm --dir im-web test:engineering`；交付前至少运行 `pnpm --dir im-web build`。

## 场景测试

常用命令：

```bash
pnpm --dir im-scenario-tests scenario:smoke
pnpm --dir im-scenario-tests scenario:group-chat
pnpm --dir im-scenario-tests scenario:cluster-ha
pnpm --dir im-scenario-tests scenario:group-call
```

`cluster-ha` 默认：

```text
node-1 HTTP=http://127.0.0.1:8088
node-1 WS=ws://127.0.0.1:8081/ws
node-2 HTTP=http://127.0.0.1:8089
node-2 WS=ws://127.0.0.1:8084/ws
```

## 常见开发任务

### 新增 HTTP API

1. 在 `Operation.java` 增加 method/path/op/auth。
2. 在 `im-api` 增加 DTO 或接口。
3. 在 `im-server/core/handler/unified` 实现 handler。
4. 在 `DispatcherFactory` 注册。
5. 在 `im-sdk/src/api` 增加方法。
6. 如果前端要用，接 `im-web` store 或页面。
7. 加测试。

### 新增跨节点状态

1. 先问这个状态是否多个节点都要看到。
2. 如果要看到，写 Redis 或 MySQL。
3. 如果并发写会冲突，用 Redis 原子操作、唯一键、事务或分布式锁。
4. 节点挂掉不能丢的东西写 MySQL 或有持久化的 MQ。
5. 不要把 Local/in-memory 实现接进生产组合根。

### 改消息发送

优先看：

```text
ChatHandler
SendMessageUseCase
PersistenceConsumer
DeliveryConsumer
DbMessageStore
RedisRouteTable
```

每次改都要考虑：

- `clientMsgId` 幂等是否仍成立。
- `persist` 和 `deliver` 的失败策略是否被破坏。
- 单聊和群聊是否都覆盖。
- 多端、多节点、离线后同步是否仍可恢复。

## 排查入口

日志：

```bash
tail -f logs/backend.log
tail -f logs/node-1.log
tail -f logs/node-2.log
tail -f logs/im-system.log
```

端口：

```bash
lsof -nP -iTCP:8083 -iTCP:8084 -sTCP:LISTEN
lsof -nP -iTCP:8081 -iTCP:8088 -iTCP:8084 -iTCP:8089 -sTCP:LISTEN
```

PID：

```bash
ls bin/pids
```

更完整的日志排查见 [logging-guide.md](logging-guide.md)。
