package com.im.bootstrap;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 消息撤回 E2E 测试（连接真实 Redis）。
 *
 * <p>测试场景：
 * <ol>
 *   <li>发送消息 → 撤回 → 接收方收到 msg_revoke 通知</li>
 *   <li>撤回不存在的 seq → 收到错误响应</li>
 * </ol>
 */
class RevokeE2ETest extends BaseE2ETest {

    private static final String SENDER = "revoke_sender_" + System.currentTimeMillis();
    private static final String RECEIVER = "revoke_receiver_" + System.currentTimeMillis();

    @BeforeAll
    static void setup() throws Exception {
        startServer(Map.of(
                "im.redis.host", "127.0.0.1",
                "im.redis.port", "6379"
        ));
    }

    @AfterAll
    static void teardown() {
        cleanupRedis(SENDER, RECEIVER);
        stopServer();
    }

    @SuppressWarnings("unchecked")
    @Test
    void testSingleChatRevoke() throws Exception {
        BlockingQueue<String> ws1In = new LinkedBlockingQueue<>();
        BlockingQueue<String> ws2In = new LinkedBlockingQueue<>();

        var ws1 = connectWs(ws1In);
        var ws2 = connectWs(ws2In);

        try {
            // ── 注册 ──
            sendAndWait(ws1, ws1In,
                    "{\"op\":\"register\",\"seq\":1,\"userId\":\"" + SENDER + "\",\"nickname\":\"Sender\"}");
            sendAndWait(ws2, ws2In,
                    "{\"op\":\"register\",\"seq\":1,\"userId\":\"" + RECEIVER + "\",\"nickname\":\"Receiver\"}");

            // ── 登录并获取 token ──
            String login1Resp = sendAndWait(ws1, ws1In,
                    "{\"op\":\"login\",\"seq\":1,\"userId\":\"" + SENDER + "\",\"platformId\":1}");
            String senderToken = extractToken(login1Resp);

            String login2Resp = sendAndWait(ws2, ws2In,
                    "{\"op\":\"login\",\"seq\":1,\"userId\":\"" + RECEIVER + "\",\"platformId\":1}");
            String receiverToken = extractToken(login2Resp);

            assertNotNull(senderToken, "sender token");
            assertNotNull(receiverToken, "receiver token");

            // ── 发送消息（需带 Authorization token）──
            String sendMsg = "{\"op\":\"chat.send\",\"seq\":1,\"Authorization\":\"" + senderToken
                    + "\",\"toUserId\":\"" + RECEIVER + "\",\"_ct\":\"text\",\"content\":{\"text\":\"Hello!\"}}";
            String sendResp = sendAndWait(ws1, ws1In, sendMsg);
            assertNotNull(sendResp, "send response");
            Map<String, Object> sendMap = readJson(sendResp);
            assertEquals("chat.send_ack", sendMap.get("op"));
            assertEquals(0, sendMap.get("code"));
            Object seqObj = ((Map<String, Object>) sendMap.get("data")).get("seq");
            assertNotNull(seqObj, "message seq should be present");
            long messageSeq = ((Number) seqObj).longValue();
            Object convIdObj = ((Map<String, Object>) sendMap.get("data")).get("conversationId");
            assertNotNull(convIdObj, "conversationId should be present");
            String convStr = convIdObj.toString();
            log.info("Message sent: conv={}, seq={}", convStr, messageSeq);

            // 等待异步持久化完成
            Thread.sleep(500);
            // 排掉已投递的消息（MessageEncoder 现在会推送消息给接收方）
            drainQueue(ws2In);

            // ── 撤回消息 ──
            String revokeReq = "{\"op\":\"msg_revoke\",\"seq\":1,\"Authorization\":\"" + senderToken
                    + "\",\"conversationId\":\"" + convStr + "\",\"messageSeq\":" + messageSeq + "}";
            String revokeResp = sendAndWait(ws1, ws1In, revokeReq);
            assertNotNull(revokeResp, "revoke response");
            Map<String, Object> revokeMap = readJson(revokeResp);
            assertEquals("msg_revoke_ack", revokeMap.get("op"));
            assertEquals(0, revokeMap.get("code"));
            log.info("Message revoked successfully");

            // ── 验证接收方收到 msg_revoke 通知 ──
            String notified = ws2In.poll(3, TimeUnit.SECONDS);
            assertNotNull(notified, "Receiver should receive revoke notification");
            Map<String, Object> notifyMap = readJson(notified);
            assertEquals("msg_revoke", notifyMap.get("op"));
            assertEquals(0, notifyMap.get("code"));
            Map<String, Object> data = (Map<String, Object>) notifyMap.get("data");
            assertEquals(convStr, data.get("conversationId"));
            assertEquals(messageSeq, ((Number) data.get("seq")).longValue());
            assertEquals(SENDER, data.get("revokerId"));
            log.info("Receiver got msg_revoke notification as expected");

        } finally {
            closeWs(ws1);
            closeWs(ws2);
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    void testRevokeNonExistentSeq() throws Exception {
        BlockingQueue<String> ws1In = new LinkedBlockingQueue<>();
        var ws1 = connectWs(ws1In);

        try {
            sendAndWait(ws1, ws1In,
                    "{\"op\":\"register\",\"seq\":1,\"userId\":\"" + SENDER + "\",\"nickname\":\"Sender\"}");
            String loginResp = sendAndWait(ws1, ws1In,
                    "{\"op\":\"login\",\"seq\":1,\"userId\":\"" + SENDER + "\",\"platformId\":1}");
            String token = extractToken(loginResp);

            // 尝试撤回一个不存在的 seq
            String revokeReq = "{\"op\":\"msg_revoke\",\"seq\":1,\"Authorization\":\"" + token
                    + "\",\"conversationId\":\"single_" + SENDER + "_" + RECEIVER + "\",\"messageSeq\":99999}";
            String resp = sendAndWait(ws1, ws1In, revokeReq);
            assertNotNull(resp, "should get error response");
            Map<String, Object> respMap = readJson(resp);
            assertEquals("msg_revoke_ack", respMap.get("op"));
            assertNotEquals(0, respMap.get("code"), "should return error code");
            log.info("Non-existent seq revoke correctly returned error: code={}", respMap.get("code"));
        } finally {
            closeWs(ws1);
        }
    }

    private String extractToken(String loginRespJson) throws Exception {
        Map<String, Object> map = readJson(loginRespJson);
        if (map.get("data") instanceof Map) {
            Object token = ((Map<String, Object>) map.get("data")).get("token");
            return token != null ? token.toString() : null;
        }
        return null;
    }
}
