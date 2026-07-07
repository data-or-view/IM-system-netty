package com.im.bootstrap;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.im.api.ApplySource;
import com.im.config.Config;
import com.im.config.ConfigLoader;
import com.im.core.db.MyBatisPlusFactory;
import com.im.core.db.mapper.FriendMapper;
import com.im.core.serialization.jackson.ObjectMapperProvider;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import org.apache.ibatis.session.SqlSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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
    private static final AtomicInteger CLIENT_MSG_COUNTER = new AtomicInteger();
    private static final Set<String> SET_PROPS = new HashSet<>();
    private static final Set<String> REGISTERED_USER_IDS = new HashSet<>();

    private static IMServer server;

    /** 当前测试使用的 WS 端口号。 */
    protected static int wsPort;

    /** 当前测试使用的 HTTP 端口号。 */
    protected static int httpPort;

    // ========== 生命周期 ==========

    /**
     * 启动 IMServer 并应用指定配置。
     * <p>默认开启 WS + HTTP，并连接真实 Redis + DB。子类通过 {@code config} 覆盖需要的配置项。</p>
     *
     * @param config 额外配置项（如 im.redis.host、im.login.multi-strategy 等）
     */
    protected static void startServer(Map<String, String> config) throws Exception {
        IMServer.resetDatabaseFailed();
        ConfigLoader.clearCustomSources();
        SET_PROPS.clear();
        REGISTERED_USER_IDS.clear();

        wsPort = PORT_COUNTER.getAndIncrement();
        httpPort = wsPort + 1000;

        // 默认配置（集群模式必须 Redis + DB）
        setProp("im.ws.port", String.valueOf(wsPort));
        setProp("im.ws.enabled", "true");
        setProp("im.http.port", String.valueOf(httpPort));
        setProp("im.http.enabled", "true");
        setProp("im.db.enabled", "true");
        setProp("im.redis.host", "127.0.0.1");
        setProp("im.redis.port", "6379");
        setProp("im.server.use-epoll", "false");
        setProp("im.token.secret", E2ETestConfig.TOKEN_SECRET);
        setProp("im.node.id", "e2e-node-" + wsPort);
        E2ETestConfig.infrastructureDefaults().forEach(BaseE2ETest::setProp);

        // 子类配置（覆盖默认）
        if (config != null) {
            for (Map.Entry<String, String> entry : config.entrySet()) {
                setProp(entry.getKey(), entry.getValue());
            }
        }

        Config configObj = ConfigLoader.reload();
        server = new IMServer(configObj);
        server.start();
        log.info("E2E server started on wsPort={}, httpPort={}", wsPort, httpPort);
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
            RedisClient client = RedisClient.create(E2ETestConfig.redisUri());
            try (StatefulRedisConnection<String, String> conn = client.connect()) {
                for (String uid : userIds) {
                    conn.sync().del("route:" + uid, "online:" + uid);
                    log.info("Redis cleanup: removed route:{} and online:{}", uid, uid);
                }
            }
            client.shutdown();
        } catch (Exception e) {
            log.warn("Redis cleanup failed (non-fatal): {}:{} {}", host, port, e.getMessage());
        }
    }

    /**
     * 清理当前 E2E JVM 内通过 {@link #registerUser(WebSocket, BlockingQueue, String)} 创建的用户路由。
     */
    protected static void cleanupRegisteredUsersRedis() {
        cleanupRedis(REGISTERED_USER_IDS.toArray(String[]::new));
        REGISTERED_USER_IDS.clear();
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

    /** 发送 HTTP JSON POST 并返回统一响应体。 */
    protected static Map<String, Object> httpPost(String path, String token, Map<String, ?> body)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + httpPort + path))
                .timeout(java.time.Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)));
        if (token != null && !token.isBlank()) {
            builder.header("Authorization", "Bearer " + token);
        }

        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(builder.build(), HttpResponse.BodyHandlers.ofString());
        assertNotNull(response.body(), "http response body");
        return readJson(response.body());
    }

    /** 注册测试用户，返回服务端真实生成的 userId。 */
    protected static E2EUser registerUser(WebSocket ws, BlockingQueue<String> incoming, String nickname)
            throws Exception {
        return registerUser(ws, incoming, nickname, E2ETestConfig.TEST_PASSWORD);
    }

    /** 注册测试用户，返回服务端真实生成的 userId。 */
    @SuppressWarnings("unchecked")
    protected static E2EUser registerUser(WebSocket ws, BlockingQueue<String> incoming, String nickname,
                                          String password) throws Exception {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("op", "register");
        req.put("seq", 1);
        req.put("nickname", nickname);
        req.put("password", password);

        String resp = sendAndWait(ws, incoming, MAPPER.writeValueAsString(req));
        assertNotNull(resp, "register response");
        Map<String, Object> map = readJson(resp);
        assertEquals("register_ack", map.get("op"), "register response: " + resp);
        assertEquals(0, map.get("code"), "register response: " + resp);
        assertInstanceOf(Map.class, map.get("data"), "register response data: " + resp);
        Map<String, Object> data = (Map<String, Object>) map.get("data");
        Object userId = data.get("userId");
        assertNotNull(userId, "register userId: " + resp);
        assertFalse(userId.toString().isBlank(), "register userId should not be blank");
        REGISTERED_USER_IDS.add(userId.toString());
        return new E2EUser(userId.toString(), password);
    }

    /** 使用测试用户登录并返回 access token。 */
    protected static String loginUser(WebSocket ws, BlockingQueue<String> incoming, E2EUser user, int platformId)
            throws Exception {
        return loginUser(ws, incoming, user.userId(), user.password(), platformId);
    }

    /** 使用指定账号密码登录并返回 access token。 */
    @SuppressWarnings("unchecked")
    protected static String loginUser(WebSocket ws, BlockingQueue<String> incoming, String userId, String password,
                                      int platformId) throws Exception {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("op", "login");
        req.put("seq", 1);
        req.put("userId", userId);
        req.put("password", password);
        req.put("platformId", platformId);

        String resp = sendAndWait(ws, incoming, MAPPER.writeValueAsString(req));
        assertNotNull(resp, "login response");
        Map<String, Object> map = readJson(resp);
        assertEquals("login_ack", map.get("op"), "login response: " + resp);
        assertEquals(0, map.get("code"), "login response: " + resp);
        assertInstanceOf(Map.class, map.get("data"), "login response data: " + resp);
        Object token = ((Map<String, Object>) map.get("data")).get("token");
        assertNotNull(token, "login token: " + resp);
        assertFalse(token.toString().isBlank(), "login token should not be blank");
        return token.toString();
    }

    /** 当前单聊会话 ID 规则：按 userId 字典序排序。 */
    protected static String singleConversationId(String userId1, String userId2) {
        String u1 = userId1.compareTo(userId2) <= 0 ? userId1 : userId2;
        String u2 = userId1.compareTo(userId2) <= 0 ? userId2 : userId1;
        return "single_" + u1 + "_" + u2;
    }

    /** 生成满足 SendMessageUseCase 幂等校验规则的客户端消息 ID。 */
    protected static String clientMsgId(String prefix) {
        String safePrefix = prefix == null || prefix.isBlank()
                ? "e2e"
                : prefix.replaceAll("[^A-Za-z0-9._:-]", "_");
        String id = safePrefix + ":" + Long.toString(System.currentTimeMillis(), 36)
                + ":" + Integer.toString(CLIENT_MSG_COUNTER.incrementAndGet(), 36);
        if (id.length() > 64) {
            id = id.substring(id.length() - 64);
        }
        while (id.length() < 8) {
            id += "0";
        }
        return id;
    }

    /** 为需要单聊发送权限的 E2E 用例准备双向好友关系。 */
    protected static void makeFriends(E2EUser user1, E2EUser user2) {
        makeFriends(user1.userId(), user2.userId());
    }

    /** 为需要单聊发送权限的 E2E 用例准备双向好友关系。 */
    protected static void makeFriends(String userId1, String userId2) {
        long now = System.currentTimeMillis();
        try (SqlSession session = MyBatisPlusFactory.openSession()) {
            FriendMapper mapper = session.getMapper(FriendMapper.class);
            int addSource = ApplySource.SEARCH.getCode();
            mapper.upsertFriend(userId1, userId2, addSource, userId1, now);
            mapper.upsertFriend(userId2, userId1, addSource, userId1, now);
            session.commit();
        }
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

    protected record E2EUser(String userId, String password) {
    }
}
