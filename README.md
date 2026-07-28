# IM System Netty

一个集群优先的全栈即时通讯系统。后端是 Java 21 + Netty，提供 WebSocket 消息通道和 HTTP REST 管理接口；前端是 React/Vite 工作台；TypeScript SDK 和场景测试用于真实客户端接入与多用户验证。

如果你是 AI 或刚接手项目的开发者，先读 [docs/ai-project-guide.md](docs/ai-project-guide.md)。那里按“部署架构、模块职责、启动脚本、配置、消息链路、测试入口”整理了当前项目事实。

## 项目结构

| 路径 | 说明 |
|------|------|
| `im-common/` | 跨模块公共异常、重试、生命周期、ID 和工具类，不依赖具体基础设施。 |
| `im-api/` | Java 接口、DTO、Operation 枚举和协议契约。 |
| `im-server/` | 服务端实现，包含启动、Netty 传输、Dispatcher、handler、use case、Redis/MySQL/RocketMQ/MinIO 装配。 |
| `im-infrastructure/` | 基础设施适配器子模块，包括配置、缓存、序列化、对象存储、幂等和 RocketMQ 消息适配。 |
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
# 空数据库或已经托管的 Version 2 数据库
bin/start-cluster.sh

# 仅用于从受支持的 v1.1 schema 升级
IM_CLUSTER_SCHEMA_OWNER_MODE=migrate bin/start-cluster.sh
```

`node-1` 是唯一 schema owner，默认以 `-Dim.db.schema=auto` 启动；脚本只有在日志确认 Version 2 初始化/校验完成并出现 `Server ready` 后，才会以 `-Dim.db.schema=none` 启动 `node-2`。`auto` 不会升级 v1.1 数据库，v1.1 必须显式选择 `migrate`。

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

## Version 2 集群升级顺序

升级时只选择一个节点操作 schema：空数据库用 `-Dim.db.schema=auto`，现有 v1.1 数据库用 `-Dim.db.schema=migrate`。等待该节点日志出现 `Blank database initialized at managed schema Version 2`、`Managed schema Version 2 validated` 或 `Schema migration to Version 2 completed`，并确认节点 ready；然后才用 `-Dim.db.schema=none` 启动其余节点。禁止多个节点并发执行 `auto` 或 `migrate`。

服务端升级前，先把旧客户端升级到当前 SDK 的 POST-policy 文件上传流程。仍调用原始 `/api/file/upload`、预签名 `PUT` 或 multipart sign/complete 的客户端与新服务端不兼容；当前流程是 `/api/file/upload/sign` 返回 `method: "POST"`、`formFields` 和 `fileField`，客户端直传 MinIO 后再调用 `/api/file/upload/complete`。

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
pnpm --dir im-scenario-tests scenario:file-upload-policy
IM_SCENARIO_NODE1_PID_FILE=bin/pids/node-1.pid \
pnpm --dir im-scenario-tests scenario:cluster-ha
```

`cluster-ha` 会在验证 PID 文件、node-1 两个监听端口和 `/health/live` 身份后向 node-1 发送 `SIGTERM`；未显式设置 `IM_SCENARIO_NODE1_PID_FILE` 时会直接报告前置条件，不会停止任何进程。

RocketMQ 真实 broker 测试见 [docs/rocketmq-integration-tests.md](docs/rocketmq-integration-tests.md)。

## 关键文档

| 文档 | 用途 |
|------|------|
| [docs/ai-project-guide.md](docs/ai-project-guide.md) | AI 和新开发者的项目快读入口。 |
| [ARCHITECTURE.md](ARCHITECTURE.md) | 后端架构、生命周期、集群消息链路。 |
| [AGENTS.md](AGENTS.md) | AI 写代码必须遵守的集群部署约束和工作入口。 |
| [docs/logging-guide.md](docs/logging-guide.md) | requestId、trace、日志排查方法。 |
| [docs/file-storage.md](docs/file-storage.md) | MinIO 文件上传/下载说明。 |
| [docs/route-redis-key-layout-migration.md](docs/route-redis-key-layout-migration.md) | 路由 Redis `tagged-v3` 全停机迁移手册。 |
| [im-scenario-tests/README.md](im-scenario-tests/README.md) | 多用户场景测试说明。 |

## 集群开发原则

这个项目按多节点部署写代码。任何跨节点共享状态都不能放在本地内存里。路由、在线状态、消息序号、会话、消息、幂等、节点发现、失败补偿必须落 Redis 或 MySQL。Local/in-memory 实现只允许作为单元测试或开发兜底，不允许进入生产装配路径。
