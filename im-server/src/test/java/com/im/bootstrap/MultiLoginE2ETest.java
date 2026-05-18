package com.im.bootstrap;

import com.im.api.MultiLoginStrategy;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 多端登录 E2E 测试（连接真实 Redis）。
 *
 * <p>测试 SAME_TERM_KICK 策略：
 * <ol>
 *   <li>WS1 登录 platformId=1 → 成功</li>
 *   <li>WS2 同用户同平台登录 → WS1 被踢，收到 kicked 通知</li>
 *   <li>WS3 同用户不同平台(platformId=2) 登录 → WS2 不被踢</li>
 * </ol>
 */
class MultiLoginE2ETest extends BaseE2ETest {

    private static final String USER_ID = "multi_e2e_" + System.currentTimeMillis();

    @BeforeAll
    static void setup() throws Exception {
        startServer(Map.of(
                "im.redis.host", "127.0.0.1",
                "im.redis.port", "6379",
                "im.login.multi-strategy", MultiLoginStrategy.SAME_TERM_KICK.name()
        ));
    }

    @AfterAll
    static void teardown() {
        cleanupRedis(USER_ID);
        stopServer();
    }

    @SuppressWarnings("unchecked")
    @Test
    void testSameTermKick() throws Exception {
        BlockingQueue<String> ws1In = new LinkedBlockingQueue<>();
        BlockingQueue<String> ws2In = new LinkedBlockingQueue<>();
        BlockingQueue<String> ws3In = new LinkedBlockingQueue<>();

        var ws1 = connectWs(ws1In);
        var ws2 = connectWs(ws2In);
        var ws3 = connectWs(ws3In);

        try {
            // ── 注册用户 ──
            String regJson = "{\"op\":\"register\",\"seq\":1,\"userId\":\"" + USER_ID + "\",\"nickname\":\"MultiTest\"}";
            String regResp = sendAndWait(ws1, ws1In, regJson);
            assertNotNull(regResp, "register response");
            assertEquals(0, readJson(regResp).get("code"));
            log.info("REGISTER OK");

            // ── WS1 登录 platformId=1（iOS）──
            String login1 = "{\"op\":\"login\",\"seq\":1,\"userId\":\"" + USER_ID + "\",\"platformId\":1}";
            String resp1 = sendAndWait(ws1, ws1In, login1);
            assertNotNull(resp1, "WS1 login response");
            Map<String, Object> m1 = readJson(resp1);
            assertEquals("login_ack", m1.get("op"));
            assertEquals(0, m1.get("code"));
            log.info("WS1 LOGIN OK (platformId=1)");

            // ── WS2 同用户同平台登录 → 应踢掉 WS1 ──
            String login2 = "{\"op\":\"login\",\"seq\":1,\"userId\":\"" + USER_ID + "\",\"platformId\":1}";
            String resp2 = sendAndWait(ws2, ws2In, login2);
            assertNotNull(resp2, "WS2 login response");
            Map<String, Object> m2 = readJson(resp2);
            assertEquals("login_ack", m2.get("op"));
            assertEquals(0, m2.get("code"));
            log.info("WS2 LOGIN OK (platformId=1)");

            // ── 验证 WS1 收到 kicked 通知 ──
            String kickedMsg = ws1In.poll(3, TimeUnit.SECONDS);
            assertNotNull(kickedMsg, "WS1 should receive kicked notification");
            Map<String, Object> km = readJson(kickedMsg);
            assertEquals("kicked", km.get("op"));
            assertEquals(0, km.get("code"));
            assertEquals("SAME_TERM_KICK",
                    ((Map<String, Object>) km.get("data")).get("reason"));
            log.info("WS1 KICKED notification verified");

            // ── WS3 同用户不同平台 platformId=2 登录 → WS2 不应被踢 ──
            String login3 = "{\"op\":\"login\",\"seq\":1,\"userId\":\"" + USER_ID + "\",\"platformId\":2}";
            String resp3 = sendAndWait(ws3, ws3In, login3);
            assertNotNull(resp3, "WS3 login response");
            Map<String, Object> m3 = readJson(resp3);
            assertEquals("login_ack", m3.get("op"));
            assertEquals(0, m3.get("code"));
            log.info("WS3 LOGIN OK (platformId=2)");

            // ── 验证 WS2 未被踢（等 2 秒确认没有 kicked 通知）──
            String ws2Msg = ws2In.poll(2, TimeUnit.SECONDS);
            if (ws2Msg != null) {
                Map<String, Object> ws2m = readJson(ws2Msg);
                assertNotEquals("kicked", ws2m.get("op"),
                        "WS2 should NOT be kicked when different platform logs in");
            }
            log.info("WS2 NOT kicked — confirmed");

        } finally {
            closeWs(ws1);
            closeWs(ws2);
            closeWs(ws3);
        }
    }
}
