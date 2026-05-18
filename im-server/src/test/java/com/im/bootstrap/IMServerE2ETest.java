package com.im.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.im.config.Config;
import com.im.config.ConfigLoader;
import com.im.config.ConfigSource;
import com.im.core.serialization.jackson.ObjectMapperProvider;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class IMServerE2ETest {

    private static final Logger log = LoggerFactory.getLogger(IMServerE2ETest.class);
    private static final ObjectMapper MAPPER = ObjectMapperProvider.get();
    private static final int WS_PORT = 18081;

    private static IMServer server;

    @BeforeAll
    static void startServer() throws Exception {
        IMServer.resetDatabaseFailed();
        ConfigLoader.register(new ConfigSource() {
            @Override
            public int order() {
                return 0;
            }

            @Override
            public Map<String, String> load() {
                Map<String, String> map = new HashMap<>();
                map.put("im.ws.port", String.valueOf(WS_PORT));
                map.put("im.ws.enabled", "true");
                map.put("im.http.enabled", "false");
                map.put("im.db.enabled", "true");
                map.put("im.redis.host", "127.0.0.1");
                map.put("im.redis.port", "6379");
                map.put("im.node.id", "e2e-test-node");
                map.put("im.server.use-epoll", "false");
                map.put("im.token.secret", "e2e-test-secret");
                return map;
            }
        });
        Config config = ConfigLoader.reload();
        server = new IMServer(config);
        server.start();
        log.info("E2E test server started on port {}", WS_PORT);
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            cleanupRedis("e2e_test_user");
            server.stop();
            log.info("E2E test server stopped");
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    void testRegisterAndLogin() throws Exception {
        LinkedBlockingQueue<String> incoming = new LinkedBlockingQueue<>();

        WebSocket ws = HttpClient.newHttpClient().newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:" + WS_PORT + "/ws"), new WebSocket.Listener() {
                    @Override
                    public void onOpen(WebSocket webSocket) {
                        webSocket.request(1);
                    }

                    @Override
                    public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                        incoming.offer(data.toString());
                        return WebSocket.Listener.super.onText(ws, data, last);
                    }
                })
                .get(10, TimeUnit.SECONDS);

        try {
            // ── Register ──
            String regJson = "{\"op\":\"register\",\"seq\":1,\"userId\":\"e2e_test_user\",\"nickname\":\"E2E\"}";
            ws.sendText(regJson, true).get(5, TimeUnit.SECONDS);

            String regResp = incoming.poll(5, TimeUnit.SECONDS);
            assertNotNull(regResp, "No register response received");
            Map<String, Object> regMap = MAPPER.readValue(regResp, Map.class);
            assertEquals("register_ack", regMap.get("op"));
            assertEquals(0, regMap.get("code"));
            log.info("REGISTER OK");

            // ── Login ──
            String loginJson = "{\"op\":\"login\",\"seq\":1,\"userId\":\"e2e_test_user\",\"platformId\":1}";
            ws.sendText(loginJson, true).get(5, TimeUnit.SECONDS);

            String loginResp = incoming.poll(5, TimeUnit.SECONDS);
            assertNotNull(loginResp, "No login response received");
            Map<String, Object> loginMap = MAPPER.readValue(loginResp, Map.class);
            assertEquals("login_ack", loginMap.get("op"));
            assertEquals(0, loginMap.get("code"));

            Map<String, Object> data = (Map<String, Object>) loginMap.get("data");
            assertNotNull(data, "login data should not be null");
            String token = (String) data.get("token");
            assertNotNull(token, "token should not be null");
            assertFalse(token.isEmpty(), "token should not be empty");
            log.info("LOGIN OK, token={}...", token.substring(0, Math.min(30, token.length())));

        } finally {
            ws.sendClose(1000, "done").get(3, TimeUnit.SECONDS);
        }
    }

    private static void cleanupRedis(String... userIds) {
        try {
            RedisClient client = RedisClient.create("redis://127.0.0.1:6379");
            try (StatefulRedisConnection<String, String> conn = client.connect()) {
                for (String uid : userIds) {
                    conn.sync().del("route:" + uid, "online:" + uid);
                }
            }
            client.shutdown();
        } catch (Exception e) {
            log.warn("Redis cleanup failed: {}", e.getMessage());
        }
    }
}
