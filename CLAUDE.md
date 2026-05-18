# IM-system-netty

Single-process Netty-based IM server with WS/HTTP unified pipeline.

## OpenIM Reference

This project references OpenIM source code for design inspiration. The source is at:

- **Absolute**: `/Users/macbook/java/IdeaProjects/github-source/data-or-view/open-im-server/`
- **Relative**: `../open-im-server/`

When designing new features, evaluating architecture changes, or debugging message flow, invoke the `openim-reference` skill to look up how OpenIM implements the equivalent functionality.

## Project Structure

| Module | Path | Role |
|--------|------|------|
| `im-api` | `im-api/` | Public interfaces (IUserManager, IMessageQueue, IRouteTable, etc.) |
| `im-server` | `im-server/` | Use cases, handlers, WS/HTTP transport, session, Netty server, main() |
| `im-infrastructure-*` | `im-infrastructure/` | 通用基础设施（序列化、缓存接口、配置、消息队列封装） |

## Key Design Decisions

- **Cluster-first**: 本项目一定是多节点部署。所有跨节点共享状态（会话、路由、在线状态、未读数等）必须写 Redis 或 MySQL，禁止用本地内存。详见 `AGENTS.md` 和 `cluster-deployment` skill。
- Single-process JVM with virtual threads (Project Loom)
- WS and HTTP share the same ApiDispatcher pipeline
- Async persistence via PersistenceConsumer (Redis Streams → MySQL)
- Online status in Redis ZSet via IRouteTable
- Interfaces in im-api, implementations injected via constructor

## Testing Policy

- **Functional / E2E tests must use real infrastructure**（Redis、MySQL 等），不能用 Local/in-memory 实现。例如 `MultiLoginE2ETest` 连接本地 Redis 验证多端登录逻辑，`IMServerE2ETest` 也应改为连接真实数据库。
- **Unit tests** 适用于与基础设施无关的纯逻辑测试（如枚举转换、校验、工具类），可以使用 mock 或纯内存对象。
- E2E 测试类命名规范：以 `E2ETest` 或 `E2E` 结尾，放在 `im-bootstrap/src/test/java/com/im/bootstrap/` 下。
- 测试之前确认本地基础设施已启动（如 `redis-cli ping`）。

## E2E 测试基类 BaseE2ETest

`im-bootstrap/src/test/java/com/im/bootstrap/BaseE2ETest.java` 提供可复用的 E2E 测试能力：

- **自动端口分配**：每次启动分配独立 WS 端口（18100+），多测试类并行无冲突
- **IMServer 生命周期**：`startServer(configMap)` 启动服务，`stopServer()` 停止并清理系统属性
- **WebSocket 工具**：`connectWs()`、`sendAndWait()`、`closeWs()` 简化客户端操作
- **Redis 清理**：`cleanupRedis(userIds)` 删除指定用户的在线状态和路由数据
- **JSON 工具**：`readJson()` 快速解析响应

### 使用示例

```java
class MyE2ETest extends BaseE2ETest {
    private static final String USER_ID = "test_" + System.currentTimeMillis();

    @BeforeAll
    static void setup() throws Exception {
        startServer(Map.of(
            "im.redis.host", "127.0.0.1",
            "im.db.enabled", "true"
        ));
    }

    @AfterAll
    static void teardown() {
        cleanupRedis(USER_ID);
        stopServer();
    }

    @Test
    void testSomething() throws Exception {
        BlockingQueue<String> in = new LinkedBlockingQueue<>();
        WebSocket ws = connectWs(in);
        try {
            String resp = sendAndWait(ws, in,
                "{\"op\":\"login\",\"userId\":\"" + USER_ID + "\"}");
            assertNotNull(resp);
            assertEquals("login_ack", readJson(resp).get("op"));
        } finally {
            closeWs(ws);
        }
    }
}
```

### 常见配置项

| 配置键 | 默认值 | 说明 |
|--------|--------|------|
| `im.redis.host` | (无) | Redis 地址，填了才启用 RedisRouteTable |
| `im.redis.port` | 6379 | Redis 端口 |
| `im.db.enabled` | false | 是否启用数据库 |
| `im.login.multi-strategy` | ALLOW_MULTIPLE | 多端登录策略 |
| `im.ws.port` | 自动分配 | WS 端口，通常无需手动设置 |
| `im.http.enabled` | false | 是否启用 HTTP |
