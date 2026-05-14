# IM System — 轻量级即时通讯系统

基于 Netty 的纯 Java 即时通讯系统，支持 WebSocket + HTTP REST 接入，面向单机开发与集群扩展设计。

## 项目结构

```
im-system/
├── im-api/         接口定义层（15+ 接口 + DTO + 枚举）
├── im-core/        核心实现层（35+ 实现类 + Handler）
├── im-bootstrap/   启动引导层（Netty ServerBootstrap）
└── pom.xml         父 POM（模块聚合）
```

### 模块依赖

```
im-bootstrap → im-core → im-api
```

## 技术栈

| 组件 | 技术选型 |
|------|---------|
| 网络层 | Netty 4.2 (WebSocket + HTTP REST) |
| 序列化 | JSON (Jackson) |
| 认证 | HMAC-SHA256 自签 Token（不依赖外部 JWT 库） |
| 消息队列 | MemoryMQ（可替换为 Kafka/RocketMQ/Pulsar） |
| 缓存 | ConcurrentHashCache（可替换为 Redis/ETCD） |
| 日志 | SLF4J + Logback |
| 构建 | Maven + JDK 21 |
| 并发模型 | Netty EventLoop + 虚拟线程业务池 |

## 快速启动

```bash
# 编译
mvn clean package -DskipTests

# 启动服务端
cd im-bootstrap
java --enable-preview -jar target/im-bootstrap-1.0.0-SNAPSHOT.jar

<!-- 客户端 SDK 已随 TCP 协议移除，Web 客户端开发中 -->
```

或使用集成脚本：

```bash
bash run-test.sh
```

## 核心特性

### 已完成（Phase A ~ C）
- [x] WebSocket + HTTP REST 接入
- [x] 用户认证：Token 签发/验证（HMAC-SHA256）
- [x] 单聊消息收发 + ACK 确认
- [x] 群聊消息收发 + 成员展开多播
- [x] 消息序号（Sequence）生成
- [x] 消息历史拉取（按 seq 范围）
- [x] 消息队列模式（MQ 解耦收发）
- [x] 多端登录策略（允许多端/踢旧/拒新）
- [x] 会话管理（Conversation，含未读/置顶/免打扰）
- [x] 好友关系链（申请/审批/删除/拉黑/备注）
- [x] RTC 信令接口（支持 LiveKit 等 SFU）
- [x] Webhook/Callback 机制
- [x] 缓存抽象层（支持 TTL + 安全降级）
- [x] 命名规范化（全项目）

### 待实现（Phase D ~ F）
- [ ] 集群化（服务发现 + 路由共享 + 跨节点转发）
- [ ] 文件/图片上传（OSS/MinIO）
- [ ] 离线推送（APNs/FCM）
- [ ] 消息撤回
- [ ] 限流熔断
- [ ] 消息已读回执
- [ ] 端到端加密

## 架构概述

```
┌──────────────┐   ┌────────────────┐
│  WS Client   │   │ HTTP Client    │
└──────┬───────┘   └───────┬────────┘
       │                   │
       ▼                   ▼
┌──────────────────────────────────────┐
│     Netty Server (WS/HTTP REST)     │
│  ┌──────────────────────────────┐   │
│  │  JsonWsCodec (JSON ↔ Cmd)   │   │
│  └──────┬───────────────────────┘   │
│         ▼                           │
│  ┌────────────────────────────────┐  │
│  │    MessageRouterHandler       │  │
│  │  ├─ AuthInterceptor          │  │
│  │  ├─ LoginHandler             │  │
│  │  ├─ ChatHandler              │  │
│  │  ├─ PullMessageHandler       │  │
│  │  └─ ConversationGet/Set      │  │
│  └──────────┬─────────────────────┘  │
└─────────────┼────────────────────────┘
              │
              ▼
┌──────────────────────────────┐
│        MQ 管道（虚拟线程）   │
│  ┌────────┐   ┌────────────┐ │
│  │ Persist│   │  Deliver   │ │
│  │Consumer│   │  Consumer  │ │
│  └───┬────┘   └──────┬─────┘ │
│      ▼                ▼       │
│  MessageStore    SessionMgr  │
│  (内存)          + RouteTbl │
└──────────────────────────────┘
```

## 通信协议

### WebSocket JSON 协议

客户端与服务端通过 WebSocket 发送 JSON 文本帧通信。帧格式为 IMCommand 的扁平 JSON 映射：

```json
{
  "_op": 1,        "_seq": 123,     "_mid": "uuid",
  "_ts": 1234567890,                "_flg": 1,
  "userId": "alice",  "token": "xxx",
  "_ct": "text",
  "_body": "eyJ0ZXh0IjoiaGVsbG8ifQ=="
}
```

| 字段 | 说明 |
|------|------|
| `_op` | 操作码（CommandType） |
| `_seq` | 消息序号 |
| `_mid` | 消息 ID |
| `_ts` | 时间戳 |
| `_flg` | 标志位（ACK 等） |
| `_ct` | 内容类型（text/image/file 等） |
| `_body` | 消息体（Base64 编码） |
| 其它 | 自定义头（如 userId, token, key 等） |

## 测试

```bash
mvn test
# 68 个测试用例全部通过
# im-api: 33, im-core: 23, im-bootstrap: 12
```

## License

MIT License
