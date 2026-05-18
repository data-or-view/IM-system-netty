# 架构设计文档

## 设计哲学

**接口先行，实现可替换。** 所有业务能力以 Java 接口定义在 `im-api` 模块，`im-server` 提供单机内存实现和 Redis/DB 实现，不改一行业务代码。

```
im-api             (契约层 — 接口 + DTO)
im-server          (实现 + 装配 — handler、transport、session、Netty、main())
im-infrastructure  (基础设施 — 序列化、缓存、配置、消息队列)
```

## 模块职责

### im-api — 接口层

纯接口与 DTO，零外部依赖。定义整个系统的能力边界：

| 类别 | 接口/类 | 职责 |
|------|---------|------|
| **会话** | `ISessionManager` | 连接会话管理（绑定/解绑/查找） |
| **路由** | `IRouteTable` | 用户到节点的路由表 |
| **节点** | `INodeDiscovery` | 节点发现与注册 |
| **集群** | `IClusterMessageBus` | 跨节点消息转发 |
| **状态** | `IClusterStateStore` | 分布式状态存储 |
| **消息** | `IMessageQueue` | 消息队列抽象 |
| **序号** | `ISequenceManager` | 消息序号生成 |
| **存储** | `IMessageStore` | 消息持久化存储 |
| **认证** | `IAuthenticator` | Token 签发/验证 |
| **用户** | `IUserManager` | 用户注册/查询/在线 |
| **群组** | `IGroupManager` | 群聊管理 |
| **好友** | `IFriendManager` | 好友关系链 |
| **会话** | `IConversationManager` | 会话列表/未读/置顶 |
| **回调** | `IWebhookManager` | Webhook 回调 |
| **通话** | `ICallManager` | RTC 信令 |
| **推送** | `IOfflinePush` | 离线推送 |
| **文件** | `IFileStorageService` | 文件存储 |
| **撤回** | `IMessageRevoke` | 消息撤回 |
| **内容** | `IMessageContent` | 消息内容类型（文本/图片/文件/信令） |

### im-server — 服务端实现

#### 统一调度管线

WS 和 HTTP 请求都经过 `ApiDispatcher` 这一条管线：

```
WebSocket Text Frame / HTTP Request
       │
       ▼
┌──────────────────────────────┐
│  WsRequestAdapter /          │
│  HttpRequestAdapter          │
│  (协议帧 → ApiRequest)       │
└──────────┬───────────────────┘
           │
           ▼
┌──────────────────────────────────────────────┐
│              ApiDispatcher                    │
│                                              │
│  ┌──────────────────────────────────────┐    │
│  │  Interceptor Chain                   │    │
│  │  ├─ TelemetryInterceptor (order=MIN) │    │
│  │  └─ AuthInterceptor    (order=MIN)   │    │
│  └──────────┬───────────────────────────┘    │
│             ▼                                │
│  ┌──────────────────────────────────────┐    │
│  │  RequestHandler (按 op 分发)          │    │
│  │  ├─ LoginHandler                     │    │
│  │  ├─ ChatHandler (chat.send/group)    │    │
│  │  ├─ MessageHandler (pull/seq/sync/search)│
│  │  ├─ FriendHandler                    │    │
│  │  ├─ GroupHandler                     │    │
│  │  ├─ UserHandler                      │    │
│  │  ├─ ConversationHandler              │    │
│  │  ├─ FileUploadHandler                │    │
│  │  ├─ FileMultipartHandler             │    │
│  │  ├─ RevokeHandler                    │    │
│  │  └─ HeartbeatHandler                 │    │
│  └──────────────────────────────────────┘    │
│                                              │
│  ┌──────────────────────────────────────┐    │
│  │  全局异常处理: ImException → 错误响应  │    │
│  └──────────────────────────────────────┘    │
└──────────────────┬───────────────────────────┘
                   │
          ┌────────┴────────┐
          ▼                  ▼
┌─────────────────┐  ┌──────────────────┐
│ PersistConsumer │  │ DeliveryConsumer │
│ (虚拟线程)      │  │ (虚拟线程)       │
│                 │  │                  │
│ 更新 Conversation│  │ local→write     │
│ 存储消息索引     │  │ remote→cluster  │
└─────────────────┘  └──────────────────┘
```

#### 认证流程

```
Client → LOGIN(seq=1, userId=alice)
  → LoginHandler 签发 Token(HMAC-SHA256)
  → 返回 {op:"login_ack", code:0, data:{token:"...", platformId:1}}

后续请求:
  Client → 业务消息(Authorization: Bearer <token>)
  → AuthInterceptor 验证 token → 注入 currentUserId
  → 放行到业务 Handler
```

#### 消息队列模式

```
ChatHandler → SendMessageUseCase       DeliveryConsumer
     │                                        ▲
     │  1. sequenceManager.nextSeq()          │
     │  2. webhookService.beforeSend()        │
     │  3. mq.publish("persist", msg) ────────┤
     │  4. mq.publish("deliver", msg) ────────┤
     │  5. webhookService.afterSend()         │
     │  6. ack to client                      │
     ▼                                        │
  (虚拟线程) 异步消费                          │
     │                                        │
     ├─ PersistConsumer: store.save + conv更新 │
     │                                        │
     └─ DeliveryConsumer:                     │
          ├─ lookupAll(userId) → 路由节点列表   │
          ├─ 本地 → sessionManager.push        │
          ├─ 远程 → clusterMessageBus.send      │
          └─ 离线 → (跳过，store 保底)          │
```

#### 关键设计决策

| 决策 | 原因 |
|------|------|
| **统一 ApiDispatcher** | WS 和 HTTP 共享同一套 handler 和拦截器，避免两套逻辑维护 |
| **虚拟线程业务池** | 每个请求创建虚拟线程，不阻塞 Netty EventLoop，简化编程模型 |
| **双层持久化** | ChatHandler write-ahead save + PersistenceConsumer 最终存储，防止消费者丢消息 |
| **字典序 conversationId** | Alice→Bob 和 Bob→Alice 映射到同一 conversationId，双方看到同一个会话 |

### im-infrastructure — 基础设施

| 子模块 | 职责 |
|--------|------|
| `im-infrastructure-common` | 通用工具（错误码、异常、生命周期、重试、线程池） |
| `im-infrastructure-config` | 配置抽象（YAML/环境变量/系统属性/组合源） |
| `im-infrastructure-serialization` | 序列化接口 + Jackson 实现 |
| `im-infrastructure-cache` | 缓存抽象（ICache + CacheStats） |
| `im-infrastructure-cache-redis` | Redis 缓存实现 |
| `im-infrastructure-message` | 消息总线抽象（MessageBus） |
| `im-infrastructure-message-kafka` | Kafka 消息总线实现 |
| `im-infrastructure-storage` | 文件存储（MinIO SDK + 分片上传） |
| `im-infrastructure-spi` | SPI 加载器 |

## 缓存架构

```
Manager (业务层)
    │
    ▼
SafeCache (安全装饰器 — 任何异常降级不传播)
    │
    ▼
ICache 实现 (ConcurrentHashCache / RedisCache)
    │
    ▼
ConcurrentHashMap / Redis (数据源)

失效策略: 写操作 → delete cache key → 下次读触发 reload
TTL 兜底: 即使不主动失效，过期后自动清理
安全设计: SafeCache + try-catch(Throwable)，缓存崩溃不影响业务
```

## Webhook 回调

```
BeforeSend: 同步 + 5s 超时 → 非 2xx 阻断消息
AfterSend:  异步虚拟线程 + 2s 超时 → 不阻塞主流程

URL 格式: {baseUrl}/{eventName_toLowerCase}
请求头: Content-Type: application/json
异常策略: 超时/网络异常 → fail-open（放行）
```

## 错误码

| 区间 | 含义 | 示例 |
|------|------|------|
| 0 | 成功 | OK |
| 4xx | 客户端错误 | 400 BAD_REQUEST, 401 UNAUTHORIZED, 404 NOT_FOUND, 409 CONFLICT, 429 RATE_LIMITED |
| 5xx | 服务端错误 | 500 INTERNAL_ERROR, 503 SERVICE_UNAVAILABLE |

所有业务异常通过 `ImException` (RuntimeException) 抛出，`ApiDispatcher` 全局捕获后返回统一错误响应：

```json
WS: {"op":"xxx_ack","seq":123,"code":401,"msg":"unauthorized","detail":"token expired"}
HTTP: {"code":401,"message":"unauthorized"}  // HTTP 401
```

## 集群演进路径

所有接口已设计为可替换实现，集群化只需新增实现类：

```
D1: RedisNodeDiscovery + RedisRouteTable（服务发现 + 路由共享）
D2: RedisClusterMessageBus（跨节点转发）
D3: DB-backed MessageStore + Manager（持久化存储）
```

详见 `AGENTS.md` 集群部署约束。
