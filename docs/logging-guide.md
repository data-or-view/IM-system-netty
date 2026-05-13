# IM 系统日志排查指南

## 1. 核心日志文件

| 文件 | 用途 | 路径 |
|------|------|------|
| 后端运行日志 | 所有业务 + 网络事件 | `/tmp/im-server-redis3.log`（最新） |
| 前端 Vite 日志 | 前端构建 | `/tmp/im-web-vite3.log` |

## 2. 日志关联方式（Trace ID）

**前后端通过 `_traceId` 字段关联：**

```
前端发: [IM] [debug] traceId=trc_xxxxx_001 event=ws.send data={op: "92", seq: "1"}
后端收: [trc_xxxxx_001] AUTH OK: userId=1111, type=USER_SEARCH
            ↓
前端收: [IM] [debug] traceId=trc_xxxxx_001 event=ws.recv data={op: "93", status: "OK"}
```

**搜索方法：** 根据前端报错的 traceId，到后端日志 grep：

```bash
grep "trc_xxxxx_001" /tmp/im-server-redis3.log
```

## 3. 前端排查（浏览器 Console）

### 查看日志
1. 打开浏览器 → F12 → Console
2. 日志格式统一为：
   ```
   [IM] [level] traceId=xxx event=ws.send data={...}
   [IM] [level] traceId=xxx event=ws.recv data={...}
   [IM] [warn]  traceId=xxx event=ws.send.fail data={reason: "not connected"}
   [IM] [warn]  traceId=noid event=ws.decode.error data={error: "..."}
   ```

### 常见问题判断

| Console 现象 | 可能原因 |
|---|---|
| 只有 `ws.send`，没有 `ws.recv` | 后端拦截器拒绝了请求（查后端日志） |
| `ws.recv` 但 store 没反应 | store 中的条件判断不对（字段名不一致） |
| `ws.send.fail (not connected)` | WebSocket 未连接或已断开 |
| `ws.decode.error` | 协议帧解析异常（magic 校验或 JSON 格式错误） |

## 4. 后端排查

```bash
tail -100 /tmp/im-server-redis3.log
grep "WARN\|ERROR" /tmp/im-server-redis3.log | tail -20
grep "456" /tmp/im-server-redis3.log        # 按 traceId 搜索
grep "1111" /tmp/im-server-redis3.log | tail -10  # 按 userId 搜索
```

### 关键日志点

**拦截器层：**
```
[trc_xxx] WHITELIST: type=LOGIN           # 登录/心跳/注册，白名单放行
[trc_xxx] Request without token: type=92   # 搜索请求没带 token → 前端没传 Authorization
[trc_xxx] Token validation failed: ...     # token 过期或无效
[trc_xxx] AUTH OK: userId=1111, type=92    # 鉴权通过
```

**Handler 层：**
```
User logged in: userId=1111, platform=Web  # 登录成功
```

**无 handler：**
```
No handler for type: 123, seqId=2          # 命令码没有对应的 Handler 注册
```

## 5. 常见排查流程

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

## 6. 后端编译重启

```bash
cd /home/admin/openclaw/workspace/im-system
mvn package -DskipTests -q
kill $(lsof -ti:8080) $(lsof -ti:8081) 2>/dev/null
setsid nohup java -Dredis.cluster.nodes="127.0.0.1:6379,127.0.0.1:6380,127.0.0.1:6381" \
  -jar im-bootstrap/target/im-bootstrap-1.0.0-SNAPSHOT.jar \
  > /tmp/im-server-redis-new.log 2>&1 &
tail -f /tmp/im-server-redis-new.log | grep "Server ready"
```
