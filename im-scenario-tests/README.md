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

```bash
pnpm --dir im-scenario-tests scenario:smoke
```

注册 1 个用户，WebSocket 登录，然后通过 HTTP 拉取用户资料。

```bash
pnpm --dir im-scenario-tests scenario:group-chat -- --users=3 --messages=1
```

注册多个用户，全部 WebSocket 登录，创建群，发送群消息，并检查其它在线成员收到推送。

```bash
pnpm --dir im-scenario-tests scenario:group-chat-perf -- --users=3 --messages=10 --concurrency=2
```

小规模群聊压测，用于检查并发发送、推送数量和基础耗时指标。

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
pnpm --dir im-scenario-tests scenario:cluster-ha
```

验证两节点集群下跨节点单聊、群聊和同用户多端推送。默认端口与 `bin/start-cluster.sh` 对齐：node-1 为 `8081/ws` + `8088`，node-2 为 `8084/ws` + `8089`。

也可以显式覆盖集群节点：

```bash
IM_SCENARIO_NODE1_HTTP_URL=http://127.0.0.1:8088 \
IM_SCENARIO_NODE1_WS_URL=ws://127.0.0.1:8081/ws \
IM_SCENARIO_NODE2_HTTP_URL=http://127.0.0.1:8089 \
IM_SCENARIO_NODE2_WS_URL=ws://127.0.0.1:8084/ws \
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
