# IM System Netty

一个集群优先的全栈即时通讯系统。后端是 Java 21 + Netty，提供 WebSocket 消息通道和 HTTP REST 管理接口；前端是 React/Vite 工作台；TypeScript SDK 和场景测试用于真实客户端接入与多用户验证。

如果你是 AI 或刚接手项目的开发者，先读 [docs/ai-project-guide.md](docs/ai-project-guide.md)。那里按“部署架构、模块职责、启动脚本、配置、消息链路、测试入口”整理了当前项目事实。

## 项目结构

| 路径 | 说明 |
|------|------|
| `im-common/` | 跨模块公共异常、重试、生命周期、ID 和工具类，不依赖具体基础设施。 |
| `im-api/` | Java 接口、DTO、Operation 枚举和协议契约。 |
| `im-server/` | 服务端实现，包含启动、Netty 传输、Dispatcher、handler、use case、Redis/MySQL/RocketMQ/MinIO 装配。 |
| `im-infrastructure/` | 基础设施适配器子模块，包括配置、缓存、序列化、对象存储、幂等、Kafka/RocketMQ 抽象与实现。 |
| `im-sdk/` | TypeScript SDK，封装 WS、HTTP、token、重连同步、消息批处理和业务 API。 |
| `im-web/` | React + Vite 前端，默认连接本地开发后端 `8083/8084`。 |
| `im-scenario-tests/` | 多用户真实 HTTP/WS 场景脚本，覆盖冒烟、群聊、跨节点投递、通话等流程。 |
| `bin/` | 本地启动、重启、停止脚本。 |
| `config/` | 部署配置模板；服务端默认从 classpath 的 `application*.yml` 加载配置。 |
| `docs/` | 运维、文件存储、RocketMQ 集成测试和 AI 快速指南。 |

## 当前架构

生产组合根在 `im-server/src/main/java/com/im/bootstrap/ServerComponentsFactory.java`。它会强制要求 Redis 和数据库：

- Redis: 路由表、在线状态、节点发现、集群 Pub/Sub、消息序号、缓存、上传会话、通话状态。
- MySQL: 用户、好友、群组、会话、消息、幂等记录、失败补偿、文件元数据、系统消息。
- MQ: `im.mq.type=redis` 使用 Redis Streams，`im.mq.type=rocketmq` 使用 RocketMQ。
- Netty: WebSocket 和 HTTP 共用 `ApiDispatcher`、拦截器和 handler。

高层链路：

```text
im-web / im-sdk
  |
  |-- WebSocket /ws: login, heartbeat, chat.send, chat.send.group, push
  |-- HTTP /api/*: user, friend, group, conversation, message pull/search, file, system
  v
TransportServer
  v
ApiDispatcher -> AuthInterceptor -> Handler -> UseCase
  |
  |-- Redis: route, online, seq, node discovery, cache, cluster bus
  |-- MySQL: durable business data
  |-- Redis Streams / RocketMQ: persist + deliver topics
  |-- MinIO: object storage
  |-- LiveKit: call room/token provider
```

## 快速启动

先构建后端：

```bash
mvn -pl im-api,im-server -am package -DskipTests
```

启动单节点开发后端，默认 `WS=8083`、`HTTP=8084`：

```bash
bin/restart-backend.sh
```

启动本地双节点集群，默认 `node-1 WS=8081 HTTP=8088`，`node-2 WS=8084 HTTP=8089`：

```bash
bin/start-cluster.sh
```

停止本地集群：

```bash
bin/stop-cluster.sh
```

启动前端：

```bash
cd im-web
pnpm dev
```

前端默认访问后端：

```text
VITE_WS_URL=ws://127.0.0.1:8083/ws
VITE_HTTP_URL=http://127.0.0.1:8084
```

如需连集群节点，显式覆盖 `VITE_WS_URL` 和 `VITE_HTTP_URL`。

## 常用验证

后端单元和集成边界测试：

```bash
mvn test
```

前端构建：

```bash
pnpm --dir im-web build
```

SDK 测试：

```bash
npm --prefix im-sdk test
```

场景测试：

```bash
pnpm --dir im-scenario-tests scenario:smoke
pnpm --dir im-scenario-tests scenario:cluster-ha
```

RocketMQ 真实 broker 测试见 [docs/rocketmq-integration-tests.md](docs/rocketmq-integration-tests.md)。

## 关键文档

| 文档 | 用途 |
|------|------|
| [docs/ai-project-guide.md](docs/ai-project-guide.md) | AI 和新开发者的项目快读入口。 |
| [ARCHITECTURE.md](ARCHITECTURE.md) | 后端架构、生命周期、集群消息链路。 |
| [AGENTS.md](AGENTS.md) | AI 写代码必须遵守的集群部署约束和工作入口。 |
| [docs/logging-guide.md](docs/logging-guide.md) | requestId、trace、日志排查方法。 |
| [docs/file-storage.md](docs/file-storage.md) | MinIO 文件上传/下载说明。 |
| [im-scenario-tests/README.md](im-scenario-tests/README.md) | 多用户场景测试说明。 |

## 集群开发原则

这个项目按多节点部署写代码。任何跨节点共享状态都不能放在本地内存里。路由、在线状态、消息序号、会话、消息、幂等、节点发现、失败补偿必须落 Redis 或 MySQL。Local/in-memory 实现只允许作为单元测试或开发兜底，不允许进入生产装配路径。
