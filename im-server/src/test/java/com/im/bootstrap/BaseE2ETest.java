package com.im.bootstrap;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.im.config.Config;
import com.im.config.ConfigLoader;
import com.im.core.serialization.jackson.ObjectMapperProvider;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * E2E 测试基类。
 *
 * <p>提供 IMServer 生命周期管理（连接真实 Redis/DB）、WebSocket 工具和 JSON 工具，
 * 子类无需重复编写基础设施启动/清理代码。
 *
 * <p>使用方式：
 * <pre>{@code
 * class MyE2ETest extends BaseE2ETest {
 *     private static final String USER_ID = "test_" + System.currentTimeMillis();
 *
 *     @BeforeAll
 *     static void setup() throws Exception {
 *         startServer(Map.of(
 *             "im.redis.host", "127.0.0.1",
 *             "im.login.multi-strategy", "SAME_TERM_KICK"
 *         ));
 *     }
 *
 *     @AfterAll
 *     static void teardown() {
 *         cleanupRedis(USER_ID);
 *         stopServer();
 *     }
 *
 *     @Test
 *     void testSomething() throws Exception {
 *         BlockingQueue<String> in = new LinkedBlockingQueue<>();
 *         WebSocket ws = connectWs(in);
 *         try {
 *             String resp = sendAndWait(ws, in, "{\"op\":\"ping\"}");
 *             assertNotNull(resp);
 *         } finally {
 *             closeWs(ws);
 *         }
 *     }
 * }
 * }</pre>
 */
public abstract class BaseE2ETest {

    protected static final Logger log = LoggerFactory.getLogger(BaseE2ETest.class);
    protected static final ObjectMapper MAPPER = ObjectMapperProvider.get();
    protected static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private static final AtomicInteger PORT_COUNTER = new AtomicInteger(18100);
    private static final Set<String> SET_PROPS = new HashSet<>();

    private static IMServer server;

    /** 当前测试使用的 WS 端口号。 */
    protected static int wsPort;

    // ========== 生命周期 ==========

    /**
     * 启动 IMServer 并应用指定配置。
     * <p>默认开启 WS，关闭 HTTP 和 DB。子类通过 {@code config} 覆盖需要的配置项。</p>
     *
     * @param config 额外配置项（如 im.redis.host、im.login.multi-strategy 等）
     */
    protected static void startServer(Map<String, String> config) throws Exception {
        IMServer.resetDatabaseFailed();
        ConfigLoader.clearCustomSources();
        SET_PROPS.clear();

        wsPort = PORT_COUNTER.getAndIncrement();

        // 默认配置（集群模式必须 Redis + DB）
        setProp("im.ws.port", String.valueOf(wsPort));
        setProp("im.ws.enabled", "true");
        setProp("im.http.enabled", "false");
        setProp("im.db.enabled", "true");
        setProp("im.redis.host", "127.0.0.1");
        setProp("im.redis.port", "6379");
        setProp("im.server.use-epoll", "false");
        setProp("im.token.secret", "e2e-test-secret");
        setProp("im.node.id", "e2e-node-" + wsPort);

        // 子类配置（覆盖默认）
        if (config != null) {
            for (Map.Entry<String, String> entry : config.entrySet()) {
                setProp(entry.getKey(), entry.getValue());
            }
        }

        Config configObj = ConfigLoader.reload();
        server = new IMServer(configObj);
        server.start();
        log.info("E2E server started on port {}", wsPort);
    }

    /**
     * 停止 IMServer 并清理系统属性。
     */
    protected static void stopServer() {
        if (server != null) {
            try {
                server.stop();
            } catch (Exception e) {
                log.warn("Error stopping server", e);
            }
            server = null;
        }
        for (String key : SET_PROPS) {
            System.clearProperty(key);
        }
        SET_PROPS.clear();
        log.info("E2E server stopped, system properties cleaned");
    }

    // ========== Redis 清理 ==========

    /**
     * 清理 Redis 中指定用户的在线状态和路由数据。
     * <p>子类在 @AfterAll 中调用以确保测试数据被清理。</p>
     *
     * @param userIds 要清理的用户 ID 列表
     */
    protected static void cleanupRedis(String... userIds) {
        String host = System.getProperty("im.redis.host", "127.0.0.1");
        String port = System.getProperty("im.redis.port", "6379");
        try {
            RedisClient client = RedisClient.create("redis://" + host + ":" + port);
            try (StatefulRedisConnection<String, String> conn = client.connect()) {
                for (String uid : userIds) {
                    conn.sync().del("route:" + uid, "online:" + uid);
                    log.info("Redis cleanup: removed route:{} and online:{}", uid, uid);
                }
            }
            client.shutdown();
        } catch (Exception e) {
            log.warn("Redis cleanup failed (non-fatal): {}", e.getMessage());
        }
    }

    // ========== WebSocket 工具 ==========

    /**
     * 创建 WebSocket 连接到测试服务。
     *
     * @param incoming 收到的消息将被放入此队列
     */
    protected static WebSocket connectWs(BlockingQueue<String> incoming) throws Exception {
        return HttpClient.newHttpClient().newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:" + wsPort + "/ws"), new WebSocket.Listener() {
                    @Override
                    public void onOpen(WebSocket ws) {
                        ws.request(1);
                    }

                    @Override
                    public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                        incoming.offer(data.toString());
                        ws.request(1);
                        return WebSocket.Listener.super.onText(ws, data, last);
                    }
                })
                .get(10, TimeUnit.SECONDS);
    }

    /** 发送 JSON 并等待响应。超时 5 秒。 */
    protected static String sendAndWait(WebSocket ws, BlockingQueue<String> incoming, String json) throws Exception {
        ws.sendText(json, true).get(5, TimeUnit.SECONDS);
        return incoming.poll(5, TimeUnit.SECONDS);
    }

    /** 安全关闭 WebSocket 连接（忽略异常）。 */
    protected static void closeWs(WebSocket ws) {
        if (ws != null) {
            try { ws.sendClose(1000, "done").get(3, TimeUnit.SECONDS); } catch (Exception ignored) {}
        }
    }

    /**
     * 从队列中清空所有当前消息（用于在发送新请求前排掉异步推送的消息）。
     */
    protected static void drainQueue(BlockingQueue<?> queue) {
        if (queue != null) {
            queue.clear();
        }
    }

    // ========== JSON 工具 ==========

    @SuppressWarnings("unchecked")
    protected static Map<String, Object> readJson(String json) throws Exception {
        return MAPPER.readValue(json, Map.class);
    }

    // ========== 内部 ==========

    private static void setProp(String key, String value) {
        System.setProperty(key, value);
        SET_PROPS.add(key);
    }
}
