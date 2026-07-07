package com.im.bootstrap;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 消息增量同步 E2E 测试（连接真实 Redis）。
 *
 * <p>测试场景：
 * <ol>
 *   <li>发送消息后接收方调用 chat.sync 拉取增量消息</li>
 *   <li>同步后再次调用 chat.sync 无增量返回</li>
 *   <li>空 seqs 调用返回空结果</li>
 * </ol>
 */
class SyncE2ETest extends BaseE2ETest {

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
    void testSyncNewMessages() throws Exception {
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
                    + "\",\"clientMsgId\":\"" + clientMsgId("sync.send")
                    + "\",\"toUserId\":\"" + receiver.userId() + "\",\"_ct\":\"text\",\"content\":{\"text\":\"Sync me!\"}}";
            String sendResp = sendAndWait(ws1, ws1In, sendMsg);
            assertNotNull(sendResp, "send response");
            Map<String, Object> sendMap = readJson(sendResp);
            assertEquals(0, sendMap.get("code"));
            long messageSeq = ((Number) ((Map<String, Object>) sendMap.get("data")).get("seq")).longValue();
            log.info("Message sent: seq={}", messageSeq);

            // 等待异步持久化完成
            Thread.sleep(500);
            // 排掉已投递的消息（MessageEncoder 现在会推送消息给接收方）
            drainQueue(ws2In);

            // ── 接收方调用 chat.sync（HTTP-only，已知 seq=0，拉取全部）──
            Map<String, Object> syncMap = httpPost("/api/msg/sync", receiverToken, Map.of(
                    "seqs", Map.of(conversationId, 0)
            ));
            assertEquals(0, syncMap.get("code"), "sync response: " + syncMap);

            Map<String, Object> syncData = (Map<String, Object>) syncMap.get("data");
            assertNotNull(syncData, "sync response should have data");
            Object syncsObj = syncData.get("syncs");
            assertInstanceOf(java.util.List.class, syncsObj, "syncs should be a list");
            java.util.List<Map<String, Object>> syncs = (java.util.List<Map<String, Object>>) syncsObj;
            assertEquals(1, syncs.size(), "should have sync entry for the conversation");
            Map<String, Object> entry = syncs.get(0);
            assertEquals(conversationId, entry.get("conversationId"));
            assertNotNull(entry.get("messages"));
            java.util.List<Map<String, Object>> msgs = (java.util.List<Map<String, Object>>) entry.get("messages");
            assertFalse(msgs.isEmpty(), "should have at least one message");
            assertNotNull(entry.get("maxSeq"));
            long maxSeq = ((Number) entry.get("maxSeq")).longValue();
            assertTrue(maxSeq >= messageSeq, "maxSeq should be >= sent message seq");
            log.info("Sync successful: {} messages, maxSeq={}", msgs.size(), maxSeq);

            // ── 再次同步应无新消息 ──
            Map<String, Object> syncAgainMap = httpPost("/api/msg/sync", receiverToken, Map.of(
                    "seqs", Map.of(conversationId, maxSeq)
            ));
            assertEquals(0, syncAgainMap.get("code"), "second sync response: " + syncAgainMap);
            Map<String, Object> syncAgainData = (Map<String, Object>) syncAgainMap.get("data");
            java.util.List<Map<String, Object>> syncsAgain = (java.util.List<Map<String, Object>>) syncAgainData.get("syncs");
            java.util.List<Map<String, Object>> emptyMsgs = (java.util.List<Map<String, Object>>) syncsAgain.get(0).get("messages");
            assertTrue(emptyMsgs.isEmpty(), "should have no new messages after syncing to maxSeq");

        } finally {
            closeWs(ws1);
            closeWs(ws2);
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    void testSyncEmptySeqs() throws Exception {
        BlockingQueue<String> ws1In = new LinkedBlockingQueue<>();
        var ws1 = connectWs(ws1In);

        try {
            E2EUser sender = registerUser(ws1, ws1In, "SenderEmptySync");
            String token = loginUser(ws1, ws1In, sender, 1);

            // 空 seqs 调用
            Map<String, Object> respMap = httpPost("/api/msg/sync", token, Map.of(
                    "seqs", Map.of()
            ));
            assertEquals(0, respMap.get("code"), "empty sync response: " + respMap);
            Map<String, Object> data = (Map<String, Object>) respMap.get("data");
            assertNotNull(data);
            java.util.List<?> syncs = (java.util.List<?>) data.get("syncs");
            assertTrue(syncs.isEmpty(), "empty seqs should return empty syncs");
        } finally {
            closeWs(ws1);
        }
    }
}
