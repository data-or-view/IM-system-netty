# 架构设计文档

## 设计哲学

**接口先行，实现可替换。** 所有业务能力以 Java 接口定义在 `im-api` 模块，`im-core` 提供单机内存实现，生产环境可原地替换为 Redis/DB/分布式实现，不改一行业务代码。

```
im-api  (契约层 — 接口 + DTO)
im-core (实现层 — 可替换)
im-bootstrap (装配层 — 注入实现)
```

## 模块职责

### im-api — 接口层（44 个文件）

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
| **文件** | `IFileStorage` | 文件存储 |
| **撤回** | `IMessageRevoke` | 消息撤回 |
| **缓存** | `ICache<K, V>` | 通用缓存抽象 |
| **内容** | `IMessageContent` | 消息内容类型（文本/图片/文件/信令） |

### im-codec — 编解码层（4 个文件）

自定义二进制协议：

```
  Bytes                    Java Object
    │                          ▲
    ▼                          │
┌──────────┐     decode()  ┌──────────┐
│ IMDecoder│ ────────────→ │ IMCommand│
└──────────┘               └──────────┘
    ▲                          │
    │          encode()        │
┌──────────┐               ┌──────────┐
│ IMEncoder│ ←──────────── │ IMCommand│
└──────────┘               └──────────┘
```

协议格式：`Magic(0xCC) + Version(0x01) + BodyLength(uint16) + Body(JSON bytes)`

### im-core — 实现层（35+ 个文件）

#### 消息处理管线

```
TCP ByteBuf                        WebSocket Frame
       │                                │
       ▼                                ▼
┌──────────────┐            ┌──────────────────────┐
│  IMDecoder   │            │  WsIMDecoder          │
│  (byte→cmd)  │            │  (ws frame → cmd)    │
└──────┬───────┘            └──────────┬───────────┘
       │                               │
       └───────────┬───────────────────┘
                   ▼
┌───────────────────────────────────────┐
│     ConnectionEventHandler           │
│  - session 绑定/解绑                  │
│  - 空闲连接检测（30s idle → close）   │
│  - 事件分发                           │
└──────────────────┬────────────────────┘
                   ▼
┌───────────────────────────────────────┐
│     MessageRouterHandler              │
│  ┌─────────────────────────────────┐  │
│  │  InterceptorChain              │  │
│  │  ├─ AuthenticationInterceptor │  │
│  │  │  (token 验证，白名单放行)   │  │
│  │  └─ xxx (扩展点)              │  │
│  └──────────┬──────────────────────┘  │
│             ▼                         │
│  ┌─────────────────────────────────┐  │
│  │  HandlerDispatcher             │  │
│  │  ├─ LoginHandler → LOGIN       │  │
│  │  ├─ ChatHandler → SINGLE/GROUP │  │
│  │  ├─ HeartbeatHandler → HK      │  │
│  │  ├─ PullMessageHandler → PULL  │  │
│  │  ├─ ConversationGetHandler     │  │
│  │  └─ ConversationSetHandler     │  │
│  └─────────────────────────────────┘  │
└──────────────────┬────────────────────┘
                   │
                   ▼
┌───────────────────────────────────────┐
│    ChatHandler（消息处理后）          │
│  1. 分配 seq (ISequenceManager)       │
│  2. BeforeSend Webhook (阻断式)       │
│  3. MessageStore.save()               │
│  4. MQ publish (persist + deliver)    │
│  5. AfterSend Webhook (异步)          │
│  6. 回 ACK 给客户端                   │
└──────────────────┬────────────────────┘
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
Client → LOGIN(seq=1, uid=alice)
  → LoginHandler 签发 Token(JWT 格式 HMAC-SHA256)
  → 返回 LOGIN_ACK(token, expire)

后续请求:
  Client → 业务消息(Authorization: Bearer <token>)
  → AuthenticationInterceptor 验证 token → 注入 uid
  → 放行到业务 Handler
```

#### 消息队列模式

```
ChatHandler (Receiver)                  DeliveryConsumer (Pusher)
     │                                        ▲
     │  1. store.save(msg)                    │
     │  2. mq.publish("deliver", msg) ────────┘
     │  3. ack to client (快速返回)
     ▼
  (虚拟线程) 异步消费
     │
     ├─ lookupAll(userId) → [Node1, Node2]
     ├─ 本地 → sessionManager.writeAndFlush
     ├─ 远程 → clusterMessageBus.sendToNode
     └─ 离线 → 跳过（持久化已有 store 保底）
```

### im-bootstrap — 启动层（3 个文件 + pipeline 配置）

双端口启动：

```java
// TCP 8080
new ServerBootstrap()
  .childHandler(new ChannelInitializer<>() {{
    addLast(IMDecoder, IMEncoder, idleHandler, eventHandler, router);
  }});

// WebSocket 8081
new ServerBootstrap()
  .childHandler(new ChannelInitializer<>() {{
    addLast(HttpServerCodec, HttpObjectAggregator,
            WebSocketServerProtocolHandler,
            WsIMDecoder, ByteBufToWsHandler,
            idleHandler, eventHandler, router);
  }});
```

两个端口共享 EventLoopGroup 和所有业务 Handler 实例。

### im-client — 客户端层（6 个文件）

轻量 SDK + QuickStart 交互式演示客户端：

```
QuickStart <userId> <targetUser> [host] [port]

交互命令:
  直接输入 → 发送文字消息
  /pull    → 拉取消息历史
  /quit    → 退出
```

## 错误处理

所有业务异常通过 `ImException` (RuntimeException) 抛出，`MessageRouterHandler` 全局捕获后返回统一错误响应：

```json
{"_err": 401, "reason": "unauthorized", "detail": "token expired"}
```

错误码规范（HTTP 风格）：

| 区间 | 含义 | 示例 |
|------|------|------|
| 4xx | 客户端错误 | 400 BAD_REQUEST, 401 UNAUTHORIZED, 404 NOT_FOUND, 409 CONFLICT, 429 RATE_LIMITED |
| 5xx | 服务端错误 | 500 INTERNAL_ERROR, 503 SERVICE_UNAVAILABLE |

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
ConcurrentHashMap (数据源)

失效策略: 写操作 → delete cache key → 下次读触发 reload
TTL 兜底: 即使不主动失效，过期后自动清理
安全设计: SafeCache + try-catch(Throwable)，缓存崩溃不影响业务
```

## 集群演进路径

所有接口已设计为可替换实现，集群化只需新增实现类：

```
D1: RedisNodeDiscovery + RedisRouteTable（服务发现 + 路由共享）
D2: NettyClusterMessageBus / Redis Pub/Sub（跨节点转发）
D3: DB-backed MessageStore + Manager（持久化存储）
```

## Webhook 回调

```
BeforeSend: 同步 + 5s 超时 → 非 2xx 阻断消息
AfterSend:  异步虚拟线程 + 2s 超时 → 不阻塞主流程

URL 格式: {baseUrl}/{eventName_toLowerCase}
请求头: Content-Type: application/json
异常策略: 超时/网络异常 → fail-open（放行）
```

## 配置参考

```yaml
# ServerConfiguration
server:
  host: 0.0.0.0
  tcpPort: 8080
  wsPort: 8081
  wsEnabled: true
  idleTimeSeconds: 30
token:
  secret: "change-in-production"
  ttlDays: 30
webhook:
  url: ""  # 空字符串 = 禁用
```

## 测试覆盖

| 模块 | 测试数 | 覆盖内容 |
|------|--------|---------|
| im-api | 33 | IMCommand 序列化、Content 校验 |
| im-codec | 19 | 编码/解码、内容序列化 |
| im-core | 23 | Session 管理、ACK、拦截器链 |

总计 75 个测试用例，全部通过。
