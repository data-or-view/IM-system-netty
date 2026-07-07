# AI 项目协作说明

本项目是一个集群优先的全栈即时通讯系统。AI 进入项目后先读：

1. [docs/ai-project-guide.md](docs/ai-project-guide.md) - 项目快读入口。
2. [README.md](README.md) - 模块和启动命令。
3. [ARCHITECTURE.md](ARCHITECTURE.md) - 后端架构、消息链路和数据模型。
4. `im-server/src/main/java/com/im/bootstrap/ServerComponentsFactory.java` - 生产组合根。
5. `im-api/src/main/java/com/im/api/Operation.java` - 协议和 HTTP 路由单点真理。

## 集群部署约束

本项目一定按多节点集群部署来写代码。所有生产路径都必须满足以下约束。

### 原则

- 不能使用本地内存存储任何跨节点共享状态。所有业务数据、会话视图、消息、路由、在线状态、节点状态、幂等记录都必须写入 Redis 或 MySQL。
- Local/in-memory 实现只允许作为单元测试或单机开发兜底，不能接入 `ServerComponentsFactory` 的生产装配路径。
- 生产组合根要求 Redis 和数据库可用。不要为了让本地启动更方便而绕过 `requireRedisConfig()` 或 `requireDatabaseEnabled()`。
- 所有消息、会话更新、幂等记录和失败补偿都要允许重复执行。

### 具体要求

| 领域 | 要求 |
|------|------|
| 会话管理 | Channel 引用只能是 JVM 本地；跨节点可见状态必须写 Redis 或 MySQL。 |
| Conversation | 会话列表、未读数、置顶、免打扰必须写 Redis cache + MySQL，禁止任何本地内存会话实现进入生产路径。 |
| 路由表 | 用户在线路由必须走 `RedisRouteTable`，禁止 LocalRouteTable 进入生产路径。 |
| 在线状态 | 平台在线状态写 Redis ZSet，心跳续期，不能只看本地连接。 |
| 消息序号 | 用 `RedisSequenceManager` 的 Redis INCR 保证多节点递增。 |
| 消息投递 | 用户连在不同节点时，通过 `RedisClusterMessageBus` 转发到目标节点。 |
| 消息持久化 | 写 MySQL；`persist` 消费和 handler 重试必须幂等。 |
| 业务 MQ | `im.mq.type=redis` 使用 Redis Streams；`im.mq.type=rocketmq` 使用 RocketMQ。 |
| 节点发现 | 节点启动时注册到 Redis，心跳保活，使用 `RedisNodeDiscovery`。 |
| 失败补偿 | MQ 发布或消费失败要写业务失败表，由补偿任务重放。 |
| 文件存储 | 文件对象走 MinIO，文件元数据写 MySQL。 |
| 通话状态 | LiveKit 房间/token 可由 provider 生成，通话状态必须走 Redis store。 |

## 新增组件检查清单

添加任何新组件时问自己：

1. 这个状态多个节点需要看到吗？如果需要，存 Redis 或 MySQL。
2. 这个操作多个节点同时执行会冲突吗？如果会，用 Redis 原子操作、MySQL 唯一键/事务或分布式锁。
3. 某个节点挂了数据会丢吗？如果会，必须持久化或放到有持久化能力的队列。
4. 这个逻辑重复执行会产生副作用吗？如果会，补幂等 key、唯一约束或状态机保护。
5. 前端或 SDK 需要这个能力吗？如果需要，同步更新 `im-sdk`、`im-web` 和场景测试。

## 关键运行入口

| 任务 | 命令 |
|------|------|
| 构建后端 | `mvn -pl im-api,im-server -am package -DskipTests` |
| 单节点开发重启 | `bin/restart-backend.sh` |
| 单节点不构建重启 | `bin/restart-backend.sh --no-build` |
| 双节点本地集群 | `bin/start-cluster.sh` |
| 停止本地集群 | `bin/stop-cluster.sh` |
| 前端开发 | `pnpm --dir im-web dev` |
| 前端工程化测试 | `pnpm --dir im-web test:engineering` |
| 场景冒烟 | `pnpm --dir im-scenario-tests scenario:smoke` |
| 跨节点场景 | `pnpm --dir im-scenario-tests scenario:cluster-ha` |

默认端口：

| 模式 | WS | HTTP |
|------|----|------|
| 单节点开发 | `8083` | `8084` |
| 集群 node-1 | `8081` | `8088` |
| 集群 node-2 | `8084` | `8089` |
| 前端 Vite | - | `39073` |

## 配置注意事项

- 服务端入口读取 classpath 内的 `application.yml` 和 `application-{im.env}.yml`。
- 根目录 `config/` 是部署模板，不会被 `Main.loadConfig()` 自动读取。
- 当前代码优先级是 `IM_*` 环境变量 > `-Dim.*` 系统属性 > env YAML > default YAML > properties。
- 本地常用环境是 `macbook-dev`，由 `-Dim.env=macbook-dev` 或 `IM_ENV=macbook-dev` 激活。
- 多机 LiveKit 部署时，`im.call.sfu-endpoint` 不能是 localhost。

## 前端工程约束

- `im-web` 页面路径和跳转目标必须通过 `src/config/routes.ts`，不要在组件里散落 `/chat`、`/login` 字符串。
- 缓存 TTL、刷新 debounce、搜索/分页数量等非视觉行为常量放在 `src/config/app-behavior.ts`。
- 登录态校验只有明确 token 失效才 `logout()`；连接失败、超时、5xx 等临时错误必须保留登录状态。
- 全局错误入口是 `src/components/GlobalErrorHandler.tsx` 和 `src/lib/app-errors.ts`。业务 catch 后如果不本地展示，使用 `notifyAppError()`。
- 改前端路由、守卫、错误处理或行为常量后，运行 `pnpm --dir im-web test:engineering`。

## 新增 API 的固定路径

1. 改 `im-api/src/main/java/com/im/api/Operation.java`。
2. 增加或调整 DTO、接口和校验。
3. 在 `im-server/src/main/java/com/im/core/handler/unified/` 写 handler。
4. 在 `DispatcherFactory` 注册 handler。
5. 如果有业务状态，写 Redis/MySQL，不写本地 Map。
6. 更新 `im-sdk/src/api/`。
7. 更新 `im-web` 页面或 store。
8. 增加 Java 测试、SDK 测试或 `im-scenario-tests` 场景。

## 测试策略

- 纯逻辑单元测试可以用 mock 或内存对象。
- 涉及 Redis、MySQL、RocketMQ、MinIO、跨节点路由、消息投递的 E2E/集成测试必须使用真实基础设施。
- E2E 测试优先复用 `im-server/src/test/java/com/im/bootstrap/BaseE2ETest.java`。
- 多用户真实协议流程优先放到 `im-scenario-tests/scenarios/`。

## 常用排查

```bash
tail -f logs/backend.log
tail -f logs/node-1.log
tail -f logs/node-2.log
tail -f logs/im-system.log
lsof -nP -iTCP:8083 -iTCP:8084 -sTCP:LISTEN
ls bin/pids
```

日志排查细节见 [docs/logging-guide.md](docs/logging-guide.md)。
