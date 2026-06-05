# IM 系统日志排查指南

## 1. 核心思路

当前日志链路分两层：

| 能力 | 用途 | 是否依赖 OTel Agent |
|------|------|---------------------|
| `requestId` | 前端、SDK、HTTP/WS 响应、后端日志之间的主关联键 | 否 |
| `trace_id` | Redis、Netty、异步链路等更完整的分布式追踪 | 是 |

排查线上问题时，优先用 `requestId`。它由 SDK 自动生成，也允许业务方自定义生成器，后端会透传到日志和响应里。

## 2. 日志格式

Logback 当前输出以下上下文字段：

```text
[trace=...] [req=...] [user=...] [op=...] [conn=...] [seq=...]
```

| 字段 | 说明 |
|------|------|
| `trace` | OpenTelemetry trace id；未启 agent 时通常是 `no-trace` |
| `req` | 单次 HTTP 请求或 WS 帧的 requestId |
| `user` | 认证后的用户 ID |
| `op` | 业务操作名，如 `chat.send`、`friend.apply` |
| `conn` | WebSocket 连接 ID |
| `seq` | WebSocket 请求序号 |

示例：

```text
12:30:23.163 [virtual-42] WARN c.i.c.d.ApiDispatcher - [trace=781ae36daa974c9bfbb9f83c5ad6a720] [req=req_mbh...] [user=332211] [op=chat.send] [conn=conn_abc] [seq=18] Handler rejected: ...
```

## 3. SDK 与后端如何传 requestId

### 3.1 HTTP

SDK 每次 HTTP 请求都会加：

```http
X-Request-Id: req_xxxxx
```

后端响应也会带回：

```http
X-Request-Id: req_xxxxx
```

浏览器 Network 面板里看到某个接口失败后，复制响应头里的 `X-Request-Id`，直接查后端日志：

```bash
grep "req_xxxxx" logs/im-system.log
```

### 3.2 WebSocket

SDK 每个 WS 请求帧都会带：

```json
{"op":"chat.send","seq":12,"_requestId":"req_xxxxx"}
```

后端 ACK 会带回：

```json
{"op":"chat.send.ack","seq":12,"code":0,"requestId":"req_xxxxx","data":{}}
```

如果前端提示发送失败，优先在 Console 或 WS Frames 里找 `requestId`，再 grep 后端日志。

### 3.3 自定义 requestId 生成器

SDK 支持传入 `requestIdFactory`，适合接入企业自己的 trace 规则：

```ts
const im = new IMClient({
  baseUrl: "http://127.0.0.1:8084",
  wsUrl: "ws://127.0.0.1:8083/ws",
  requestIdFactory: () => `req_${Date.now()}_${crypto.randomUUID()}`,
});
```

## 4. OpenTelemetry 全链路追踪

### 4.1 原理

使用 OpenTelemetry Java Agent 在 JVM 启动时自动插桩：

| 组件 | 自动追踪内容 |
|------|-------------|
| Netty | HTTP 请求 method、path、status、耗时 |
| Lettuce / Redis | Redis 命令、耗时、地址 |
| Logback | 自动注入 `trace_id` / `span_id` 到 MDC |

业务代码在 `ApiDispatcher` 中为每个 HTTP 请求或 WS 帧创建 span。启用 OTel 后，同一个请求内的业务日志会拥有相同 `trace`。

### 4.2 启动方式

开发环境可直接使用重启脚本启动普通日志链路：

```bash
bin/restart-backend.sh
```

如果需要 OTel 控制台 span，可手动启动：

```bash
mvn -pl im-server -am package -DskipTests

java -javaagent:im-server/target/agent/opentelemetry-javaagent-2.27.0.jar \
  -Dotel.service.name=im-system \
  -Dotel.traces.exporter=console \
  -Dotel.metrics.exporter=none \
  -jar im-server/target/im-server-1.0.0-SNAPSHOT.jar
```

生产环境可对接 Jaeger / OTLP Collector：

```bash
java -javaagent:im-server/target/agent/opentelemetry-javaagent-2.27.0.jar \
  -Dotel.service.name=im-system \
  -Dotel.traces.exporter=otlp \
  -Dotel.exporter.otlp.endpoint=http://jaeger:4318 \
  -Dotel.metrics.exporter=none \
  -jar im-server/target/im-server-1.0.0-SNAPSHOT.jar
```

## 5. 常用排查命令

```bash
# 实时查看日志
tail -f logs/im-system.log

# 按 requestId 搜索，最推荐
grep "req_xxxxx" logs/im-system.log

# 按用户搜索
grep "\[user=332211\]" logs/im-system.log

# 按 operation 搜索
grep "\[op=chat.send\]" logs/im-system.log

# 只看异常
grep "ERROR\|WARN" logs/im-system.log

# 查看最近 200 行异常上下文
tail -n 200 logs/im-system.log | grep "ERROR\|WARN\|req_xxxxx"
```

## 6. 常见排查流程

```text
前端提示失败
  ↓
1. 从 HTTP 响应头或 WS ACK / Frame 里拿 requestId
  ↓
2. grep requestId 查后端日志
  ↓
3. 看 op、user、conn、seq 是否符合预期
  ↓
4. 如果是业务拒绝，看 Validation / Unauthorized / Forbidden / Persistence 日志
  ↓
5. 如果 requestId 找不到，说明请求没到后端或前端使用了旧 SDK 构建产物
```

## 7. 后续增强方向

| 方向 | 价值 |
|------|------|
| MQ / 异步消费继续携带 requestId | 消息从发送、落库、跨节点投递到 ACK 可完整追踪 |
| 日志增加 `messageId` / `conversationId` / `groupId` | 排查聊天链路更快 |
| 重启脚本支持 `--otel-console` | 开发时一键打开 span 输出 |
| 前端错误弹窗展示 requestId | 用户报错时可直接带定位 ID |
