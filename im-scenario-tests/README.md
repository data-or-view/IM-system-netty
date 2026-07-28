# IM Scenario Tests

`im-scenario-tests` 是开发/测试专用的多用户场景测试基座，不属于正式服务代码路径。

它的定位不是替代 `im-server` 单元测试或 `im-web` Playwright，而是补上 IM 系统最难手测的部分：多用户、多 WebSocket、多业务事件的端到端场景。

## 目录职责

- `src/config.ts`：读取测试环境配置。
- `src/http-client.ts`：用真实 HTTP API 访问后端。
- `src/ws-client.ts`：用真实 WebSocket 协议连接后端。
- `src/message-content.ts`：统一解析字符串/对象形态的消息内容，避免场景脚本用字符串拼接做脆弱判断。
- `src/scenario-user.ts`：封装测试用户注册、登录、连接。
- `scenarios/`：可执行业务场景脚本。
- `test/`：测试基座自己的单元测试。

## 环境变量

默认连接本地开发端口：

```bash
IM_SCENARIO_HTTP_URL=http://127.0.0.1:8084
IM_SCENARIO_WS_URL=ws://127.0.0.1:8083/ws
IM_SCENARIO_PASSWORD=123456
IM_SCENARIO_TIMEOUT_MS=5000
```

如果后端端口不同，可以临时覆盖：

```bash
IM_SCENARIO_HTTP_URL=http://127.0.0.1:18084 \
IM_SCENARIO_WS_URL=ws://127.0.0.1:18081/ws \
pnpm --dir im-scenario-tests scenario:smoke
```

## 命令

```bash
pnpm --dir im-scenario-tests test
```

编译并运行测试基座单元测试。

### 场景分层

| 层级 | 命令 | 何时运行 |
|------|------|----------|
| 静态基座 | `pnpm --dir im-scenario-tests scenario:ci` | CI 默认运行；只验证 TypeScript 编译和测试基座配置，不要求后端启动。 |
| 冒烟 | `pnpm --dir im-scenario-tests scenario:smoke` | 后端单节点启动后快速确认 HTTP/WS 基础链路。 |
| 核心业务 | `pnpm --dir im-scenario-tests scenario:core` | 单节点或本地开发集群稳定后，覆盖群聊、群通话、离线同步、申请通知、系统消息和会话副作用。 |
| 混沌/幂等 | `pnpm --dir im-scenario-tests scenario:chaos` | 改消息投递、幂等、重试逻辑后运行。 |
| P0 本地门禁 | `IM_SCENARIO_NODE1_PID_FILE=../bin/pids/node-1.pid pnpm --dir im-scenario-tests scenario:p0` | 本地双节点集群和依赖服务已启动后运行，包含 smoke、core、chaos，最后执行会停止 node-1 的 cluster-ha。单节点层默认打到 node-1。 |
| 全量场景 | `IM_SCENARIO_NODE1_PID_FILE=../bin/pids/node-1.pid pnpm --dir im-scenario-tests scenario:full` | 发布前或大重构后运行全部场景，最后执行会停止 node-1 的 cluster-ha。单节点层默认打到 node-1。 |

`scenario:p0` 和 `scenario:full` 需要真实 Redis/MySQL/MQ/MinIO 依赖、本地双节点后端，以及显式的 `IM_SCENARIO_NODE1_PID_FILE` 停机授权。相对路径由 `im-scenario-tests` 目录解析，因此 `bin/start-cluster.sh` 生成的 PID 文件写作 `../bin/pids/node-1.pid`。默认会把非 cluster 场景的 `IM_SCENARIO_HTTP_URL` / `IM_SCENARIO_WS_URL` 指向 node-1 (`8088` / `8081`)；如果你改了集群端口，同时覆盖 `IM_SCENARIO_HTTP_URL`、`IM_SCENARIO_WS_URL` 和 `IM_SCENARIO_NODE*_URL`。CI 默认只运行 `scenario:ci`，避免把环境凭据问题误报成代码失败。

两个组合场景都把破坏性的 `cluster-ha` 放在最后一步。成功运行后 node-1 会保持停止状态；继续使用本地集群前，先运行 `bin/stop-cluster.sh`，再运行 `bin/start-cluster.sh` 恢复双节点。

```bash
IM_SCENARIO_NODE1_PID_FILE=../bin/pids/node-1.pid pnpm --dir im-scenario-tests scenario:p0
IM_SCENARIO_NODE1_PID_FILE=../bin/pids/node-1.pid pnpm --dir im-scenario-tests scenario:full
```

```bash
pnpm --dir im-scenario-tests scenario:smoke
```

注册 1 个用户，WebSocket 登录，然后通过 HTTP 拉取用户资料。

```bash
pnpm --dir im-scenario-tests scenario:group-chat -- --users=3 --messages=1
```

注册多个用户，全部 WebSocket 登录，创建群，发送群消息，并检查其它在线成员收到每条群消息推送。

```bash
pnpm --dir im-scenario-tests scenario:group-chat-perf -- --users=3 --messages=10 --concurrency=2
```

小规模群聊压测，用于检查并发发送、最终推送可达和基础耗时指标。

```bash
pnpm --dir im-scenario-tests scenario:group-call -- --users=3 --type=video
```

注册多个用户并验证群通话开始、加入、离开、结束的状态流转和信令推送。

```bash
pnpm --dir im-scenario-tests scenario:group-join-system
```

创建免审核群，用户直接加群，并验证群系统消息实时推送和历史消息落库。

```bash
pnpm --dir im-scenario-tests scenario:system-message
```

验证系统消息收件箱、已读状态和基础列表接口。

```bash
pnpm --dir im-scenario-tests scenario:friend-group-side-effects
```

验证好友审批后单聊会话、默认好友分组，以及创建群后的群会话副作用。

```bash
pnpm --dir im-scenario-tests scenario:offline-sync
```

接收方离线后发送单聊消息，重连后通过 `/api/msg/sync` 增量拉取离线消息，并确认单聊会话可见。

```bash
pnpm --dir im-scenario-tests scenario:friend-apply-notify
```

验证好友申请实时推送、待处理列表、未处理数量，以及同意后的申请人推送和双方好友列表。

```bash
pnpm --dir im-scenario-tests scenario:group-apply-notify
```

验证加群申请实时推送、待处理列表、未处理数量、审批通过通知、成员列表，以及审批后的群系统消息。

```bash
IM_SCENARIO_NODE1_PID_FILE=../bin/pids/node-1.pid \
pnpm --dir im-scenario-tests scenario:cluster-ha
```

验证两节点集群下跨节点单聊、群聊、同平台 session 清理、并发群通话容量上限，以及 node-1 退出后由 node-2 投递单聊超时。该场景会发送 `SIGTERM` 并让 node-1 保持停止，必须放在其它场景之后；运行后按上文说明重启双节点。默认端口与 `bin/start-cluster.sh` 对齐：node-1 为 `8081/ws` + `8088`，node-2 为 `8084/ws` + `8089`。

场景默认从 `../logs/node-1.log` 等待服务端 `im.session.cleaned` 事件，确保断线清理完成后才验证存活 session。如果节点由其它方式启动，使用 `IM_SCENARIO_NODE1_LOG_FILE` 指向对应的 node-1 日志文件。`IM_SCENARIO_CALL_TIMEOUT_SECONDS` 必须与服务端 `im.call.timeout-seconds` 一致并大于 `IM_SCENARIO_NODE1_EXIT_TIMEOUT_SECONDS`（默认 20 秒），否则场景会在发起通话前拒绝运行。

也可以显式覆盖集群节点：

```bash
IM_SCENARIO_NODE1_HTTP_URL=http://127.0.0.1:8088 \
IM_SCENARIO_NODE1_WS_URL=ws://127.0.0.1:8081/ws \
IM_SCENARIO_NODE2_HTTP_URL=http://127.0.0.1:8089 \
IM_SCENARIO_NODE2_WS_URL=ws://127.0.0.1:8084/ws \
IM_SCENARIO_NODE1_PID_FILE=../bin/pids/node-1.pid \
pnpm --dir im-scenario-tests scenario:cluster-ha
```

## 设计边界

- 这里的脚本只服务开发/测试，不放进正式 handler 或正式启动链路。
- 场景脚本尽量走公开 HTTP/WS 协议，不直接调用后端内部类，这样更接近真实客户端。
- 默认规模要小，避免开发阶段误触发压力测试；需要压测时显式传入更大的 `--users` 和 `--messages`。
- 测试用户由后端生成 userId，脚本不假设前端可生成业务 ID。
- WebSocket 推送断言优先使用 `markPushCursor()` + `waitForPushAfter()`，避免被历史推送误命中。
- 异步落库、异步消费类断言使用条件轮询，避免固定 `sleep` 造成慢机器误报。

## 后续可以补的场景

- `reconnect`：断线重连后继续收消息。
- `single-chat-read-receipt`：单聊已读回执、未读数变化和多端一致性。
- `message-retry-idempotency`：重复 `clientMsgId`、网络重试和幂等投递。
