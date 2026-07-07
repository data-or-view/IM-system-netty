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

    @BeforeAll
    static void setup() throws Exception {
        startServer(Map.of(
                "im.login.multi-strategy", MultiLoginStrategy.SAME_TERM_KICK.name()
        ));
    }

    @AfterAll
    static void teardown() {
        cleanupRegisteredUsersRedis();
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
            E2EUser user = registerUser(ws1, ws1In, "MultiTest");
            log.info("REGISTER OK");

            // ── WS1 登录 platformId=1（iOS）──
            loginUser(ws1, ws1In, user, 1);
            log.info("WS1 LOGIN OK (platformId=1)");

            // ── WS2 同用户同平台登录 → 应踢掉 WS1 ──
            loginUser(ws2, ws2In, user, 1);
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
            loginUser(ws3, ws3In, user, 2);
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
