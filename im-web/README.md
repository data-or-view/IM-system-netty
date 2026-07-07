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

## 工程化测试

```bash
pnpm --dir im-web test:engineering
```

这组轻量测试用于守住前端工程约束：路由集中配置、鉴权错误分类、错误边界导航、行为常量收敛、旧示例入口清理，以及大模块按职责拆分。

## 关键文件

| 文件 | 说明 |
|------|------|
| `src/sdk/im-sdk.ts` | 创建 SDK 单例，配置 WS/HTTP、token、重连同步。 |
| `src/config/runtime.ts` | 本地开发默认端口和请求超时。 |
| `src/config/routes.ts` | 前端路由和 redirect 目标集中配置。 |
| `src/config/app-behavior.ts` | 缓存 TTL、刷新 debounce、分页数量等非视觉行为常量。 |
| `src/lib/app-errors.ts` | 鉴权错误分类和全局错误事件。 |
| `src/App.tsx` | 登录态校验、路由、通话 Provider。 |
| `src/components/GlobalErrorHandler.tsx` | 监听 SDK error、运行时异常和未处理 Promise，并做 toast 去重。 |
| `src/pages/ChatLayout.tsx` | 聊天工作台布局。 |
| `src/components/ChatArea.tsx` | 消息区编排层，具体展示和副作用拆到 `src/components/chat/*`。 |
| `src/components/chat/*` | 消息头部、消息列表、输入区、历史加载和群通话状态。 |
| `src/components/Sidebar.tsx` | 左侧栏外壳和弹窗编排，列表/条目/功能 rail 在 `src/components/sidebar/*`。 |
| `src/components/sidebar/*` | 会话、好友、群组列表项和侧栏工具入口。 |
| `src/store/store.tsx` | 全局状态 Provider 编排层。 |
| `src/store/store-types.ts` | Store 公共状态、action 和 context 类型。 |
| `src/store/store-reducer.ts` | Store 初始状态和 reducer。 |
| `src/store/store-helpers.ts` | Store 缓存、持久化和会话派生 helper。 |
| `src/store/useStoreSdkEvents.ts` | SDK push/event 到 store 的副作用桥接。 |
| `src/store/domain.ts` | 会话、消息、推送事件的领域合并逻辑。 |
| `src/components/call/*` | LiveKit 通话 UI、Provider、配置、错误和房间连接逻辑。 |
| `src/pages/GroupInfoPage.tsx` | 群资料页编排层。 |
| `src/pages/group-info/*` | 群资料表单、成员列表、群管理操作 hook 和群资料文案工具。 |

## 前端约束

- 新增页面或跳转时优先改 `src/config/routes.ts`，业务组件不要散落 `/chat`、`/login` 字符串。
- 非视觉行为数值优先放进 `src/config/app-behavior.ts`，例如缓存 TTL、请求数量、debounce。
- 登录态校验只有明确 token 失效才 logout；连接失败、超时、服务端 5xx 要保留当前登录态。
- 业务主动 catch 的错误要么本地明确展示，要么通过 `notifyAppError()` 交给全局错误处理。
- `ChatArea`、`Sidebar`、`CallProvider`、`GroupInfoPage` 和 `store` 保持编排层职责；新增复杂逻辑优先放到相邻子目录、hook、reducer 或 helper。

## 与后端的关系

- WebSocket 用于登录、心跳、发送消息和接收推送。
- HTTP 用于用户、好友、群组、会话、历史消息、文件、系统消息等业务接口。
- `im-sdk` 会把服务端 push 转成前端事件，例如 `message`、`friendRequest`、`groupApply`、`systemMessage`、`messageRevoked`。
- 前端默认连单节点开发后端。如果要测集群，请显式设置 `VITE_WS_URL` 和 `VITE_HTTP_URL` 到目标节点。

更多项目背景见 [../docs/ai-project-guide.md](../docs/ai-project-guide.md)。
