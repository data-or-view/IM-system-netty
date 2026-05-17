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

    private static final String SENDER = "sync_sender_" + System.currentTimeMillis();
    private static final String RECEIVER = "sync_receiver_" + System.currentTimeMillis();
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
    void testSyncNewMessages() throws Exception {
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
                    + "\",\"toUserId\":\"" + RECEIVER + "\",\"_ct\":\"text\",\"content\":{\"text\":\"Sync me!\"}}";
            String sendResp = sendAndWait(ws1, ws1In, sendMsg);
            assertNotNull(sendResp, "send response");
            Map<String, Object> sendMap = readJson(sendResp);
            assertEquals(0, sendMap.get("code"));
            long messageSeq = ((Number) ((Map<String, Object>) sendMap.get("data")).get("seq")).longValue();
            log.info("Message sent: seq={}", messageSeq);

            // 等待异步持久化完成
            Thread.sleep(500);

            // ── 接收方调用 chat.sync（已知 seq=0，拉取全部）──
            String syncReq = "{\"op\":\"chat.sync\",\"seq\":1,\"Authorization\":\"" + receiverToken
                    + "\",\"seqs\":{\"" + CONVERSATION_ID + "\":0}}";
            String syncResp = sendAndWait(ws2, ws2In, syncReq);
            assertNotNull(syncResp, "sync response");
            Map<String, Object> syncMap = readJson(syncResp);
            assertEquals("chat.sync_ack", syncMap.get("op"));
            assertEquals(0, syncMap.get("code"));

            Map<String, Object> syncData = (Map<String, Object>) syncMap.get("data");
            assertNotNull(syncData, "sync response should have data");
            Object syncsObj = syncData.get("syncs");
            assertInstanceOf(java.util.List.class, syncsObj, "syncs should be a list");
            java.util.List<Map<String, Object>> syncs = (java.util.List<Map<String, Object>>) syncsObj;
            assertEquals(1, syncs.size(), "should have sync entry for the conversation");
            Map<String, Object> entry = syncs.get(0);
            assertEquals(CONVERSATION_ID, entry.get("conversationId"));
            assertNotNull(entry.get("messages"));
            java.util.List<Map<String, Object>> msgs = (java.util.List<Map<String, Object>>) entry.get("messages");
            assertFalse(msgs.isEmpty(), "should have at least one message");
            assertNotNull(entry.get("maxSeq"));
            long maxSeq = ((Number) entry.get("maxSeq")).longValue();
            assertTrue(maxSeq >= messageSeq, "maxSeq should be >= sent message seq");
            log.info("Sync successful: {} messages, maxSeq={}", msgs.size(), maxSeq);

            // ── 再次同步应无新消息 ──
            String syncAgain = "{\"op\":\"chat.sync\",\"seq\":1,\"Authorization\":\"" + receiverToken
                    + "\",\"seqs\":{\"" + CONVERSATION_ID + "\":" + maxSeq + "}}";
            String syncAgainResp = sendAndWait(ws2, ws2In, syncAgain);
            assertNotNull(syncAgainResp, "second sync response");
            Map<String, Object> syncAgainMap = readJson(syncAgainResp);
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
            sendAndWait(ws1, ws1In,
                    "{\"op\":\"register\",\"seq\":1,\"userId\":\"" + SENDER + "\",\"nickname\":\"Sender\"}");
            String loginResp = sendAndWait(ws1, ws1In,
                    "{\"op\":\"login\",\"seq\":1,\"userId\":\"" + SENDER + "\",\"platformId\":1}");
            String token = extractToken(loginResp);

            // 空 seqs 调用
            String resp = sendAndWait(ws1, ws1In,
                    "{\"op\":\"chat.sync\",\"seq\":1,\"Authorization\":\"" + token + "\",\"seqs\":{}}");
            assertNotNull(resp);
            Map<String, Object> respMap = readJson(resp);
            assertEquals("chat.sync_ack", respMap.get("op"));
            assertEquals(0, respMap.get("code"));
            Map<String, Object> data = (Map<String, Object>) respMap.get("data");
            assertNotNull(data);
            java.util.List<?> syncs = (java.util.List<?>) data.get("syncs");
            assertTrue(syncs.isEmpty(), "empty seqs should return empty syncs");
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
