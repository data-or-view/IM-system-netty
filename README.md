# IM System — 轻量级即时通讯系统

基于 Netty 的纯 Java 即时通讯系统，支持 WebSocket + HTTP REST 接入，面向单机开发与集群扩展设计。

## 项目结构

```
im-system/
├── im-api/              接口定义层（15+ 接口 + DTO + 枚举）
├── im-server/           服务端实现（handler、transport、session、Netty、main()）
├── im-infrastructure/   基础设施（序列化、缓存、配置、消息队列封装）
└── pom.xml              父 POM（模块聚合）
```

### 模块依赖

```
im-server → im-api
im-server → im-infrastructure
```

## 技术栈

| 组件 | 技术选型 |
|------|---------|
| 网络层 | Netty 4.1 (WebSocket + HTTP REST) |
| 序列化 | JSON (Jackson) |
| 认证 | HMAC-SHA256 自签 Token（不依赖外部 JWT 库） |
| 消息队列 | Redis Streams / MemoryMQ（可替换） |
| 缓存 | ConcurrentHashCache / Redis（可替换） |
| 日志 | SLF4J + Logback + OpenTelemetry |
| 构建 | Maven + JDK 21 |
| 并发模型 | Netty EventLoop + 虚拟线程业务池 |

## 快速启动

```bash
# 编译
mvn clean package -DskipTests

# 启动服务端
cd im-server
java --enable-preview -jar target/im-server-1.0.0-SNAPSHOT.jar
```

或使用集成脚本：

```bash
bash start-cluster.sh
```

## 核心特性

### 已完成
- [x] WebSocket + HTTP REST 接入（统一 ApiDispatcher 管线）
- [x] 用户认证：Token 签发/验证（HMAC-SHA256）
- [x] 单聊消息收发 + ACK 确认
- [x] 群聊消息收发 + 成员展开多播
- [x] 消息序号（Sequence）生成
- [x] 消息历史拉取（按 seq 范围）
- [x] 消息搜索（关键字、类型、时间、发送者过滤）
- [x] 消息队列模式（MQ 解耦收发）
- [x] 多端登录策略（允许多端/踢旧/拒新）
- [x] 会话管理（Conversation，含未读/置顶/免打扰）
- [x] 好友关系链（申请/审批/删除/拉黑/列表/申请管理）
- [x] 群组管理（创建/加入/退出/踢人/解散/全员禁言）
- [x] Webhook/Callback 机制（发送前阻断、发送后异步通知）
- [x] 缓存抽象层（支持 TTL + 安全降级）
- [x] 全链路追踪（OpenTelemetry）

### 待实现
- [ ] 集群化（服务发现 + 路由共享 + 跨节点转发）
- [ ] 离线推送（APNs/FCM）
- [ ] 消息已读回执
- [ ] 端到端加密

## 架构概述

```
┌──────────────┐   ┌────────────────┐
│  WS Client   │   │ HTTP Client    │
└──────┬───────┘   └───────┬────────┘
       │                   │
       ▼                   ▼
┌──────────────────────────────────────────────┐
│         Netty Server (WS/HTTP REST)          │
│  ┌────────────────────────────────────────┐  │
│  │  WsRequestAdapter / HttpRequestAdapter │  │
│  │  (WS frame ↔ ApiRequest)              │  │
│  └──────────────┬─────────────────────────┘  │
│                 ▼                            │
│  ┌────────────────────────────────────────┐  │
│  │           ApiDispatcher                │  │
│  │  ┌─────────────────────────────────┐  │  │
│  │  │  Interceptor Chain              │  │  │
│  │  │  ├─ TelemetryInterceptor       │  │  │
│  │  │  └─ AuthInterceptor            │  │  │
│  │  └──────────┬──────────────────────┘  │  │
│  │             ▼                         │  │
│  │  ┌─────────────────────────────────┐  │  │
│  │  │  Handler (操作名 → handler)     │  │  │
│  │  │  ├─ LoginHandler               │  │  │
│  │  │  ├─ ChatHandler                │  │  │
│  │  │  ├─ MessageHandler             │  │  │
│  │  │  ├─ FriendHandler/GroupHandler │  │  │
│  │  │  └─ ...                        │  │  │
│  │  └─────────────────────────────────┘  │  │
│  └────────────────────────────────────────┘  │
└──────────────────────┬───────────────────────┘
                       │
              ┌────────┴────────┐
              ▼                  ▼
┌─────────────────────┐  ┌──────────────────────┐
│  PersistenceConsumer│  │  DeliveryConsumer    │
│  (虚拟线程)          │  │  (虚拟线程)          │
│                     │  │                      │
│  MessageStore.save  │  │  local→Session推送    │
│  Conversation更新   │  │  remote→Cluster转发   │
└─────────────────────┘  └──────────────────────┘
```

## 通信协议

### WebSocket JSON 协议

客户端与服务端通过 WebSocket 发送 JSON 文本帧通信：

```json
{
  "op": "user.search",
  "seq": 12345,
  "keyword": "abc",
  "limit": 10
}
```

响应格式：

```json
{
  "op": "user.search_ack",
  "seq": 12345,
  "code": 0,
  "data": { ... }
}
```

错误响应：

```json
{
  "op": "user.search_ack",
  "seq": 12345,
  "code": 400,
  "msg": "bad request",
  "detail": "keyword is required"
}
```

| 字段 | 说明 |
|------|------|
| `op` | 操作名（如 `user.search`），响应自动追加 `_ack` |
| `seq` | 客户端请求序号，服务端原样回传 |
| `code` | 状态码（0=成功，4xx=客户端错误，5xx=服务端错误） |
| `data` | 业务数据（成功时） |
| `msg` | 错误消息（失败时） |
| `detail` | 错误详情（失败时） |

## 测试

```bash
mvn test
# 需要本地 Redis 运行 E2E 测试
```

## License

MIT License
