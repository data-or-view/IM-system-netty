package com.im.bootstrap;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 已读回执 / 未读数 E2E 测试（连接真实 Redis）。
 *
 * <p>测试场景：
 * <ol>
 *   <li>发送消息后接收方调用 conversation.read 重置未读数</li>
 * </ol>
 */
class UnreadE2ETest extends BaseE2ETest {

    private static final String SENDER = "unread_sender_" + System.currentTimeMillis();
    private static final String RECEIVER = "unread_receiver_" + System.currentTimeMillis();
    private static final String CONVERSATION_ID;

    static {
        String u1 = SENDER.compareTo(RECEIVER) <= 0 ? SENDER : RECEIVER;
        String u2 = SENDER.compareTo(RECEIVER) <= 0 ? RECEIVER : SENDER;
        CONVERSATION_ID = "single_" + u1 + "_" + u2;
    }

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
    void testMarkReadResetsUnreadCount() throws Exception {
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

            // ── 登录获取 token ──
            String login1Resp = sendAndWait(ws1, ws1In,
                    "{\"op\":\"login\",\"seq\":1,\"userId\":\"" + SENDER + "\",\"platformId\":1}");
            String senderToken = extractToken(login1Resp);

            String login2Resp = sendAndWait(ws2, ws2In,
                    "{\"op\":\"login\",\"seq\":1,\"userId\":\"" + RECEIVER + "\",\"platformId\":1}");
            String receiverToken = extractToken(login2Resp);

            assertNotNull(senderToken, "sender token");
            assertNotNull(receiverToken, "receiver token");

            // ── 发送消息 ──
            String sendMsg = "{\"op\":\"chat.send\",\"seq\":1,\"Authorization\":\"" + senderToken
                    + "\",\"toUserId\":\"" + RECEIVER + "\",\"_ct\":\"text\",\"content\":{\"text\":\"Hello!\"}}";
            String sendResp = sendAndWait(ws1, ws1In, sendMsg);
            assertNotNull(sendResp, "send response");
            Map<String, Object> sendMap = readJson(sendResp);
            assertEquals(0, sendMap.get("code"));
            long messageSeq = ((Number) ((Map<String, Object>) sendMap.get("data")).get("seq")).longValue();
            log.info("Message sent: conversationId={}, seq={}", CONVERSATION_ID, messageSeq);

            // 等待异步持久化 + 会话更新完成
            Thread.sleep(1000);

            // ── 接收方调用已读 ──
            String readReq = "{\"op\":\"conversation.read\",\"seq\":1,\"Authorization\":\"" + receiverToken
                    + "\",\"conversationId\":\"" + CONVERSATION_ID + "\",\"readSeq\":" + messageSeq + "}";
            String readResp = sendAndWait(ws2, ws2In, readReq);
            assertNotNull(readResp, "read response");
            Map<String, Object> readMap = readJson(readResp);
            assertEquals("conversation.read_ack", readMap.get("op"));
            assertEquals(0, readMap.get("code"));
            Map<String, Object> readData = (Map<String, Object>) readMap.get("data");
            assertNotNull(readData, "read response should have data");
            assertEquals(CONVERSATION_ID, readData.get("conversationId"));
            // 已读后未读数应为 0
            assertEquals(0, ((Number) readData.get("unreadCount")).intValue(),
                    "unreadCount should be 0 after markRead");
            log.info("MarkRead succeeded: unreadCount=0");

        } finally {
            closeWs(ws1);
            closeWs(ws2);
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
