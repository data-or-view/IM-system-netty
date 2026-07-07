# im-web

`im-web` 是 IM System 的 React/Vite 前端工作台，使用 `im-sdk` 连接后端 WebSocket 和 HTTP API。

## 运行

```bash
pnpm --dir im-web dev
```

默认端口：

```text
Web:  http://127.0.0.1:39073
WS:   ws://127.0.0.1:8083/ws
HTTP: http://127.0.0.1:8084
```

覆盖后端地址：

```bash
VITE_WS_URL=ws://127.0.0.1:8081/ws \
VITE_HTTP_URL=http://127.0.0.1:8088 \
pnpm --dir im-web dev
```

## 构建

```bash
pnpm --dir im-web build
```

## 关键文件

| 文件 | 说明 |
|------|------|
| `src/sdk/im-sdk.ts` | 创建 SDK 单例，配置 WS/HTTP、token、重连同步。 |
| `src/config/runtime.ts` | 本地开发默认端口和请求超时。 |
| `src/App.tsx` | 登录态校验、路由、通话 Provider。 |
| `src/pages/ChatLayout.tsx` | 聊天工作台布局。 |
| `src/components/ChatArea.tsx` | 消息区。 |
| `src/components/Sidebar.tsx` | 会话、好友、群组入口。 |
| `src/store/store.tsx` | 全局状态容器。 |
| `src/store/domain.ts` | 会话、消息、推送事件的领域合并逻辑。 |
| `src/components/call/*` | LiveKit 通话 UI 和状态。 |

## 与后端的关系

- WebSocket 用于登录、心跳、发送消息和接收推送。
- HTTP 用于用户、好友、群组、会话、历史消息、文件、系统消息等业务接口。
- `im-sdk` 会把服务端 push 转成前端事件，例如 `message`、`friendRequest`、`groupApply`、`systemMessage`、`messageRevoked`。
- 前端默认连单节点开发后端。如果要测集群，请显式设置 `VITE_WS_URL` 和 `VITE_HTTP_URL` 到目标节点。

更多项目背景见 [../docs/ai-project-guide.md](../docs/ai-project-guide.md)。
