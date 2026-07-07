# IM Scenario Tests

`im-scenario-tests` 是开发/测试专用的多用户场景测试基座，不属于正式服务代码路径。

它的定位不是替代 `im-server` 单元测试或 `im-web` Playwright，而是补上 IM 系统最难手测的部分：多用户、多 WebSocket、多业务事件的端到端场景。

## 目录职责

- `src/config.ts`：读取测试环境配置。
- `src/http-client.ts`：用真实 HTTP API 访问后端。
- `src/ws-client.ts`：用真实 WebSocket 协议连接后端。
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

## 设计边界

- 这里的脚本只服务开发/测试，不放进正式 handler 或正式启动链路。
- 场景脚本尽量走公开 HTTP/WS 协议，不直接调用后端内部类，这样更接近真实客户端。
- 默认规模要小，避免开发阶段误触发压力测试；需要压测时显式传入更大的 `--users` 和 `--messages`。
- 测试用户由后端生成 userId，脚本不假设前端可生成业务 ID。

## 后续可以补的场景

- `single-chat`：好友申请、审批、单聊消息投递。
- `offline-sync`：离线后发送消息，重新登录后增量同步。
- `friend-apply-notify`：好友申请和审批实时通知。
- `group-apply-notify`：加群申请和审批实时通知。
- `reconnect`：断线重连后继续收消息。
- `group-call`：群视频开始、加入、离开、结束状态流转。
