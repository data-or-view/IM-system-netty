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
    void testMarkReadResetsUnreadCount() throws Exception {
        BlockingQueue<String> ws1In = new LinkedBlockingQueue<>();
        BlockingQueue<String> ws2In = new LinkedBlockingQueue<>();

        var ws1 = connectWs(ws1In);
        var ws2 = connectWs(ws2In);

        try {
            // ── 注册 ──
            E2EUser sender = registerUser(ws1, ws1In, "Sender");
            E2EUser receiver = registerUser(ws2, ws2In, "Receiver");
            makeFriends(sender, receiver);
            String conversationId = singleConversationId(sender.userId(), receiver.userId());

            // ── 登录获取 token ──
            String senderToken = loginUser(ws1, ws1In, sender, 1);
            String receiverToken = loginUser(ws2, ws2In, receiver, 1);

            // ── 发送消息 ──
            String sendMsg = "{\"op\":\"chat.send\",\"seq\":1,\"Authorization\":\"" + senderToken
                    + "\",\"clientMsgId\":\"" + clientMsgId("unread.send")
                    + "\",\"toUserId\":\"" + receiver.userId() + "\",\"_ct\":\"text\",\"content\":{\"text\":\"Hello!\"}}";
            String sendResp = sendAndWait(ws1, ws1In, sendMsg);
            assertNotNull(sendResp, "send response");
            Map<String, Object> sendMap = readJson(sendResp);
            assertEquals(0, sendMap.get("code"));
            long messageSeq = ((Number) ((Map<String, Object>) sendMap.get("data")).get("seq")).longValue();
            log.info("Message sent: conversationId={}, seq={}", conversationId, messageSeq);

            // 等待异步持久化 + 会话更新完成
            Thread.sleep(1000);
            // 排掉已投递的消息（MessageEncoder 现在会推送消息给接收方）
            drainQueue(ws2In);

            // ── 接收方调用已读（conversation.read 是 HTTP-only 操作）──
            Map<String, Object> readMap = httpPost("/api/conversation/read", receiverToken, Map.of(
                    "conversationId", conversationId,
                    "readSeq", messageSeq
            ));
            assertEquals(0, readMap.get("code"), "read response: " + readMap);
            Map<String, Object> readData = (Map<String, Object>) readMap.get("data");
            assertNotNull(readData, "read response should have data");
            assertEquals(conversationId, readData.get("conversationId"));
            // 已读后未读数应为 0
            assertEquals(0, ((Number) readData.get("unreadCount")).intValue(),
                    "unreadCount should be 0 after markRead");
            log.info("MarkRead succeeded: unreadCount=0");

        } finally {
            closeWs(ws1);
            closeWs(ws2);
        }
    }
}
