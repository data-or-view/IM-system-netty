# 架构设计文档

本文描述当前代码的真实运行架构。快速入口和常用命令见 [docs/ai-project-guide.md](docs/ai-project-guide.md)。

## 设计原则

1. 接口先行：`im-api` 定义接口、DTO、`Operation` 和协议契约。
2. 集群优先：生产组合根强制要求 Redis 和 MySQL，不能依赖本地内存保存跨节点状态。
3. 单管线：WebSocket 和 HTTP 请求都进入 `ApiDispatcher`，共享认证、追踪、异常处理和 handler。
4. 持久化优先：消息、会话、用户、好友、群组、幂等和失败补偿都落 MySQL。
5. Redis 做协调：路由、在线状态、节点发现、消息序号、集群 Pub/Sub、缓存、上传会话和通话状态都走 Redis。

## 模块职责

```text
im-api
  接口、DTO、Operation、错误码、消息内容类型、集群协议对象

im-server
  Main / IMServer / ServerRuntime
  TransportServer / WsServerBootstrap / HttpServerBootstrap
  ApiDispatcher / Interceptor / unified handlers
  usecase、manager、Redis/MySQL/RocketMQ/MinIO/LiveKit 实现

im-infrastructure
  config、common、cache、serialization、storage、idempotency、message queue

im-sdk
  TypeScript 客户端 SDK，封装 WebSocket、HTTP、token、重连同步和业务 API

im-web
  React/Vite 聊天工作台，消费 im-sdk

im-scenario-tests
  多用户真实协议场景测试
```

## 启动生命周期

入口是 `com.im.bootstrap.Main`：

```text
Main
  -> loadConfig()
  -> new IMServer(config)
  -> ServerComponentsFactory.create(config)
  -> ServerRuntime.start()
```

`ServerRuntime.start()` 的顺序很重要：

```text
1. RedisNodeDiscovery.start()
2. RedisNodeDiscovery.register(node)
3. RedisClusterMessageBus.start()
4. IMessageQueue.start()
5. PersistenceConsumer.start()
6. DeliveryConsumer.start()
7. BusinessMessageDlqCompensator.start()
8. TransportServer.start()
9. RequestAdmission.open()
```

传输层最后打开，避免节点还没注册、队列还没订阅时客户端请求已经进来。

停止顺序相反：先关闭请求入口并等待请求排空，再停消费者、队列、集群总线、节点发现，最后清理本地连接和 Redis 资源。

## 运行时组件装配

生产装配集中在 `ServerComponentsFactory`：

| 能力 | 当前实现 | 说明 |
|------|----------|------|
| Session | `RedisSessionManager` | JVM 本地保存 Channel 引用；多端登录策略写 Redis。 |
| RouteTable | `RedisRouteTable` | 用户在线路由、平台在线状态和节点反向索引写 Redis。 |
| NodeDiscovery | `RedisNodeDiscovery` | 节点注册到 Redis，10s 心跳，30s TTL。 |
| ClusterMessageBus | `RedisClusterMessageBus` | Redis Pub/Sub，节点专属频道和广播频道。 |
| Sequence | `RedisSequenceManager` | `INCR im:seq:{conversationId}` 保证多节点递增。 |
| MessageStore | `DbMessageStore` | MySQL `im_messages` 等表。 |
| Conversation | `CachedConversationManager` + `DbConversationManager` | Redis cache + MySQL 用户会话视图。 |
| User | `CachedUserManager` + `DbUserManager` | Redis cache + MySQL 用户资料和路由查询。 |
| Friend | `DbFriendManager` | MySQL 好友、黑名单、申请。 |
| Group | `CachedGroupManager` + `DbGroupManager` | Redis cache + MySQL 群资料、成员、申请。 |
| MQ | `RedisMessageQueue` 或 `RocketMqMessageQueue` | 由 `im.mq.type` 选择。 |
| Idempotency | `WzgSendMessageIdempotency` | MySQL 记录 `clientMsgId` 幂等。 |
| File | `MinioFileStorageService` + MySQL metadata | 直传签名、分片上传、文件元数据。 |
| Call | `LiveKitCallManager` + Redis call state | 单聊/群聊通话信令状态。 |

`ServerComponentsFactory` 会在 Redis 或数据库未配置时直接启动失败。这是生产安全边界，不要为了“本地方便”绕回 Local 实现。

## 请求管线

```text
WebSocket Text Frame / HTTP Request
  -> WsRequestAdapter / HttpRequestAdapter
  -> ApiDispatcher
     -> TelemetryInterceptor
     -> AuthInterceptor
     -> RequestHandler
  -> WsResponseWriter / HttpResponseWriter
```

`Operation` 是协议单点真理：

- WS 根据 JSON 帧里的 `op` 查找。
- HTTP 根据 method + path 查找。
- 是否需要认证由 `Operation.requireAuth()` 决定。

主要 handler：

| Handler | Operations |
|---------|------------|
| `LoginHandler` / `RegisterHandler` | `login`, `register` |
| `UserHandler` | 用户注册、搜索、资料查询、更新 |
| `FriendHandler` | 好友申请、审批、删除、黑名单、申请列表 |
| `GroupHandler` | 建群、入群、退群、成员、申请、禁言、解散 |
| `ConversationHandler` | 会话列表、设置、已读 |
| `ChatHandler` | `chat.send`, `chat.send.group` |
| `MessageHandler` | 拉历史、拉 seq、增量同步、搜索 |
| `RevokeHandler` | 消息撤回 |
| `FileUploadHandler` / `FileDirectTransferHandler` / `FileMultipartHandler` | 文件上传、下载签名、分片上传 |
| `SystemMessageHandler` | 系统频道、站内信、管理员发布 |
| `GroupCallHandler` | 群通话开始、加入、离开、结束、活跃查询 |
| `HeartbeatHandler` | WS 心跳和在线状态续期 |

## 消息发送链路

```text
Client
  -> WS chat.send / chat.send.group
  -> ChatHandler
  -> SendMessageUseCase
     -> require clientMsgId
     -> send policy check
     -> webhook beforeSend
     -> RedisSequenceManager.nextSequence(conversationId)
     -> publish persist topic
     -> publish deliver topic
     -> webhook afterSend
  -> ACK {status, messageId, conversationId, seq}
```

`clientMsgId` 必填，格式为 `[A-Za-z0-9._:-]{8,64}`。幂等 key 是：

```text
send:{fromUserId}:{conversationId}:{clientMsgId}
```

`persist` 是强依赖，发布失败会返回错误；`deliver` 是可恢复依赖，发布失败会写业务失败表，由 `BusinessMessageDlqCompensator` 后续补偿。

## 持久化与投递

```text
persist topic
  -> PersistenceConsumer
     -> SingleMessageStore / GroupMessageStore
     -> DbMessageStore
     -> ConversationManager.updateOnMessage()

deliver topic
  -> DeliveryConsumer
     -> RedisRouteTable.lookupAllBindings(userId)
     -> local session: write to Channel
     -> remote session: RedisClusterMessageBus.sendToNode()
```

单聊投递按目标用户的所有在线绑定精确推送。群聊先从 `IGroupManager.getMemberIds(groupId)` 展开成员，再逐个查路由并行推送。离线用户不走本地内存队列，消息已在 MySQL 持久化，后续通过拉取/同步补齐。

## Redis 数据模型

| Key | 用途 |
|-----|------|
| `route:{userId}` | Hash，字段为 `platformId:sessionId`，值含 nodeId 和过期时间。 |
| `online:{userId}` | ZSet，平台在线状态，score 是过期时间。 |
| `route_node:{nodeId}` | Set，节点反向路由索引，用于节点下线清理。 |
| `im:node:{nodeId}` | 节点信息，TTL 30s。 |
| `im:nodes:alive` | 当前活跃节点集合。 |
| `im:node:{nodeId}:msgs` | Redis Pub/Sub 节点专属频道。 |
| `im:bus:broadcast` | Redis Pub/Sub 广播频道。 |
| `im:seq:{conversationId}` | 会话消息序号。 |
| `im:mq:stream:{topic}` | Redis Streams 业务消息流。 |
| `im:mq:group:{topic}` | Redis Streams consumer group。 |
| `cache:*` | 用户、群、会话等业务缓存。 |

## MySQL 数据模型

Schema 在 `im-server/src/main/resources/db/schema.sql`。

主要表：

```text
im_users
im_friends
im_friend_requests
im_blacklist
im_refresh_tokens
im_groups
im_group_members
im_group_requests
im_conversations
im_messages
im_message_read_states
im_message_visibility
im_idempotency_records
im_message_send_failures
im_sequences
im_seq_users
im_objects
im_sync_versions
im_sync_changes
im_system_channels
im_system_messages
im_system_message_inbox
```

`im_conversations` 是用户视图，每个用户有自己的会话行。置顶、免打扰、未读、删除等操作不共享。

`im_messages` 对 `(conversation_id, seq)` 和 `client_msg_id` 做唯一约束。消费者重复执行时应视为幂等。

## 配置加载

`Main.loadConfig()` 根据 `-Dim.env` 或 `IM_ENV` 注册 `classpath:application-{env}.yml`，然后调用 `ConfigLoader.load()`。

按当前代码，优先级是：

```text
1. IM_* 环境变量
2. -Dim.* 系统属性
3. classpath:application-{env}.yml
4. classpath:application.yml
5. application.properties
```

注意：根目录 `config/` 是部署模板/配置副本，不会被 `Main.loadConfig()` 自动读取。当前 jar 运行时读取的是 classpath 内的 `im-server/src/main/resources/application*.yml`，再被环境变量和 `-D` 覆盖。

## 本地运行模式

单节点开发：

```text
bin/restart-backend.sh
  env=macbook-dev
  nodeId=macbook-dev
  WS=8083
  HTTP=8084
  log=logs/backend.log
  pid=bin/pids/backend.pid
```

双节点集群：

```text
bin/start-cluster.sh
  node-1 WS=8081 HTTP=8088 log=logs/node-1.log
  node-2 WS=8084 HTTP=8089 log=logs/node-2.log
```

前端：

```text
im-web pnpm dev
  dev server=39073
  default WS=ws://127.0.0.1:8083/ws
  default HTTP=http://127.0.0.1:8084
```

## 新增 API 的落点

新增一个业务能力时，通常要改这些地方：

1. `im-api/src/main/java/com/im/api/Operation.java` 增加 op / HTTP path / 认证要求。
2. `im-api` 增加或更新 DTO、接口、枚举。
3. `im-server/src/main/java/com/im/core/handler/unified/` 增加 handler 或扩展现有 handler。
4. `DispatcherFactory` 注册 handler。
5. 如果涉及共享状态，写 Redis 或 MySQL，不写本地内存。
6. `im-sdk/src/api/` 增加 TS API。
7. `im-web/src/` 接 UI 或状态。
8. 增加单元测试、E2E 或 `im-scenario-tests` 场景。

## 参考文档

- [docs/ai-project-guide.md](docs/ai-project-guide.md)
- [AGENTS.md](AGENTS.md)
- [docs/logging-guide.md](docs/logging-guide.md)
- [docs/file-storage.md](docs/file-storage.md)
- [docs/rocketmq-integration-tests.md](docs/rocketmq-integration-tests.md)
