# IM 系统日志排查指南

## 1. OpenTelemetry 全链路追踪

### 1.1 原理

使用 OpenTelemetry Java Agent 在 JVM 启动时自动插桩：

| 组件 | 自动追踪内容 |
|------|-------------|
| **Netty** | HTTP 请求的 method、path、status、耗时 |
| **Lettuce (Redis)** | 每条命令的 statement、耗时、地址 |
| **虚拟线程** | 跨线程自动传播 trace context |
| **Logback** | 自动注入 `trace_id` / `span_id` 到 MDC，日志直接关联 |

业务代码在 `ApiDispatcher` 中为每个请求（含 WS 帧）创建 span，`TelemetryInterceptor` 注入用户行为属性。

### 1.2 启动方式

```bash
# 1. 编译（自动下载 OTel Agent 到 target/agent/）
mvn package -DskipTests -q

# 2. 启动（开发环境：控制台输出 span）
java -javaagent:im-bootstrap/target/agent/opentelemetry-javaagent-2.27.0.jar \
     -Dotel.service.name=im-system \
     -Dotel.traces.exporter=console \
     -Dotel.metrics.exporter=none \
     -jar im-bootstrap/target/im-bootstrap-1.0.0-SNAPSHOT.jar

# 3. 生产环境：对接 Jaeger/Zipkin
java -javaagent:im-bootstrap/target/agent/opentelemetry-javaagent-2.27.0.jar \
     -Dotel.service.name=im-system \
     -Dotel.traces.exporter=otlp \
     -Dotel.exporter.otlp.endpoint=http://jaeger:4318 \
     -Dotel.metrics.exporter=none \
     -jar im-bootstrap/target/im-bootstrap-1.0.0-SNAPSHOT.jar
```

### 1.3 日志效果

```
# 格式：[时间] [线程] 级别 类 - [trace_id] [user_id] 消息

# 请求入口（ApiDispatcher 创建 span → 有 trace_id）
18:54:28.298 [virtual-77] INFO  c.i.c.u.LocalUserManager - [69ba4bb6f2d3687b] [] User registered: userId=caller_...

# 同一请求链（trace_id 一致）
18:54:28.336 [virtual-80] INFO  c.i.c.s.SessionManager     - [59af5890cd67c0a] [] User bound: userId=caller_...

# 带用户属性
18:54:28.373 [virtual-82] INFO  c.i.c.c.LiveKitCallManager - [6f1f915cae4226f] [] Room created: room=room_...

# 异步回调（独立 span）
18:54:31.399 [scheduler-0] INFO  c.i.c.c.CallStateManager   - [a1b2c3d4e5f6789] [] Call timeout fired: room=...

# 系统启动（无请求上下文 → no-trace）
18:54:28.025 [main] INFO  c.i.b.IMServer - [no-trace] [] Server started: ...
```

### 1.4 关键配置项

| 环境变量 / 系统属性 | 默认值 | 说明 |
|---------------------|--------|------|
| `OTEL_SERVICE_NAME` | `im-system` | 服务名，Jaeger/日志中区分服务 |
| `OTEL_TRACES_EXPORTER` | `otlp` | `console`=控制台输出, `otlp`=对接后端, `none`=关闭 |
| `OTEL_METRICS_EXPORTER` | `otlp` | 建议设 `none`（暂不需要 metrics） |
| `OTEL_LOGS_EXPORTER` | `otlp` | 日志导出（暂不需要） |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | `http://localhost:4318` | OTLP 接收地址 |

### 1.5 Span 属性说明

每个业务 span 携带以下属性（由 `TelemetryInterceptor` 注入）：

| 属性 | 示例 | 来源 |
|------|------|------|
| `app.operation` | `user.register`、`chat.send` | 请求操作名 |
| `app.user.id` | `user_12345` | AuthInterceptor 认证后 |
| `app.conversation.id` | `conv_abc` | 请求参数中的 conversationId |
| `app.group.id` | `group_xyz` | 请求参数中的 groupId |

---

## 2. 日志文件

日志同时输出到控制台和文件：

| 文件 | 路径 | 滚动策略 |
|------|------|---------|
| 运行日志 | `logs/im-system.log` | 按天 + 100MB 滚动，保留 7 天 |

```bash
# 实时查看
tail -f logs/im-system.log

# 按 traceId 搜索
grep "69ba4bb6f2d3687b" logs/im-system.log

# 按用户搜索
grep "\[user_12345\]" logs/im-system.log

# 只看异常
grep "ERROR\|WARN" logs/im-system.log
```

---

## 3. 前后端关联

前后端通过自定义 `_traceId` 字段关联（非 OTel trace_id，由前端生成）：

```
前端发: [IM] [debug] traceId=trc_xxxxx_001 event=ws.send data={op: "92", seq: "1"}
后端收: [trc_xxxxx_001] AUTH OK: userId=1111, type=USER_SEARCH
            ↓
前端收: [IM] [debug] traceId=trc_xxxxx_001 event=ws.recv data={op: "93", status: "OK"}
```

搜索方法：根据前端报错的 traceId，到后端日志 grep：

```bash
grep "trc_xxxxx_001" logs/im-system.log
```

---

## 4. 常见排查流程

```
用户操作 → 前端没反应？
    ↓
1. 打开 Console 看有没有 ws.send/ws.recv
    ↓
有 send 没 recv → 后端 grep 该 traceId → 看拦截器是否拒了
    ↓
有 send 有 recv → store 处理逻辑有问题（条件/字段名不匹配）
    ↓
没 send → 前端组件逻辑没走到该代码路径
```

---

## 5. 编译重启

```bash
cd /Users/macbook/java/IdeaProjects/github-source/data-or-view/IM-system-netty

# 编译
mvn package -DskipTests -q

# 重启（开发环境，带 OTel 追踪）
kill $(lsof -ti:8081) 2>/dev/null
nohup java -javaagent:im-bootstrap/target/agent/opentelemetry-javaagent-2.27.0.jar \
  -Dotel.service.name=im-system \
  -Dotel.traces.exporter=console \
  -Dotel.metrics.exporter=none \
  -jar im-bootstrap/target/im-bootstrap-1.0.0-SNAPSHOT.jar \
  > logs/console.log 2>&1 &

tail -f logs/im-system.log | grep "Server ready"
```
