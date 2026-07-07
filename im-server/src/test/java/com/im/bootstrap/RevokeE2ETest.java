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

    @BeforeAll
    static void setup() throws Exception {
        startServer(Map.of());
    }

    @AfterAll
    static void teardown() {
        cleanupRegisteredUsersRedis();
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
            E2EUser sender = registerUser(ws1, ws1In, "Sender");
            E2EUser receiver = registerUser(ws2, ws2In, "Receiver");
            makeFriends(sender, receiver);

            // ── 登录并获取 token ──
            String senderToken = loginUser(ws1, ws1In, sender, 1);
            loginUser(ws2, ws2In, receiver, 1);

            // ── 发送消息（需带 Authorization token）──
            String sendMsg = "{\"op\":\"chat.send\",\"seq\":1,\"Authorization\":\"" + senderToken
                    + "\",\"clientMsgId\":\"" + clientMsgId("revoke.send")
                    + "\",\"toUserId\":\"" + receiver.userId() + "\",\"_ct\":\"text\",\"content\":{\"text\":\"Hello!\"}}";
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

            // ── 撤回消息（msg_revoke 是 HTTP-only 操作，成功后仍通过 WS 推送撤回通知）──
            Map<String, Object> revokeMap = httpPost("/api/msg/revoke", senderToken, Map.of(
                    "conversationId", convStr,
                    "messageSeq", messageSeq
            ));
            assertEquals(0, revokeMap.get("code"), "revoke response: " + revokeMap);
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
            assertEquals(sender.userId(), data.get("revokerId"));
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
            E2EUser sender = registerUser(ws1, ws1In, "SenderNonExistent");
            E2EUser receiver = registerUser(ws1, ws1In, "ReceiverNonExistent");
            String token = loginUser(ws1, ws1In, sender, 1);
            String conversationId = singleConversationId(sender.userId(), receiver.userId());

            // 尝试撤回一个不存在的 seq
            Map<String, Object> respMap = httpPost("/api/msg/revoke", token, Map.of(
                    "conversationId", conversationId,
                    "messageSeq", 99999
            ));
            assertNotEquals(0, respMap.get("code"), "should return error code");
            log.info("Non-existent seq revoke correctly returned error: code={}", respMap.get("code"));
        } finally {
            closeWs(ws1);
        }
    }
}
