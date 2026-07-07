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
 * 音视频通话 E2E 测试。
 *
 * <p>测试 INVITE → CALLING 流程：
 * <ol>
 *   <li>主叫发送 ContentType.SIGNAL + INVITE</li>
 *   <li>服务端创建 LiveKit 房间并签发 token</li>
 *   <li>主叫收到 roomId + callerToken</li>
 *   <li>被叫通过推送消息收到 CALLING 信令（含 calleeToken）</li>
 * </ol>
 */
class CallE2ETest extends BaseE2ETest {

    @BeforeAll
    static void setup() throws Exception {
        startServer(Map.of(
                "im.call.enabled", "true",
                "im.call.api-key", "devkey",
                "im.call.api-secret", "im-system-livekit-secret-2024",
                "im.call.sfu-endpoint", "ws://localhost:7880",
                "im.call.timeout-seconds", "3"
        ));
    }

    @AfterAll
    static void teardown() {
        cleanupRegisteredUsersRedis();
        stopServer();
    }

    @SuppressWarnings("unchecked")
    @Test
    void testCallInviteFlow() throws Exception {
        BlockingQueue<String> callerIn = new LinkedBlockingQueue<>();
        BlockingQueue<String> calleeIn = new LinkedBlockingQueue<>();

        var caller = connectWs(callerIn);
        var callee = connectWs(calleeIn);

        try {
            // ── 注册两个用户 ──
            E2EUser callerUser = registerUser(caller, callerIn, "Caller");
            E2EUser calleeUser = registerUser(callee, calleeIn, "Callee");
            String callerId = callerUser.userId();
            String calleeId = calleeUser.userId();
            makeFriends(callerUser, calleeUser);
            log.info("Both users registered");

            // ── 两个用户登录并获取 token ──
            String callerToken = loginUser(caller, callerIn, callerUser, 1);
            loginUser(callee, calleeIn, calleeUser, 1);
            log.info("Both users logged in");

            // ── 主叫发送 INVITE 信令（带 Authorization header） ──
            // ContentType 用枚举名 "signal", SignalingAction 用枚举名 "INVITE"
            String inviteJson = "{\"op\":\"chat.send\",\"seq\":10,"
                    + "\"clientMsgId\":\"" + clientMsgId("call.invite") + "\","
                    + "\"toUserId\":\"" + calleeId + "\","
                    + "\"_ct\":\"signal\","
                    + "\"content\":{\"action\":\"INVITE\",\"sdp\":\"dummy_sdp_offer\"},"
                    + "\"Authorization\":\"Bearer " + callerToken + "\"}";
            String inviteResp = sendAndWait(caller, callerIn, inviteJson);
            assertNotNull(inviteResp, "INVITE response");
            Map<String, Object> respMap = readJson(inviteResp);
            assertEquals("chat.send_ack", respMap.get("op"));
            assertEquals(0, respMap.get("code"));

            Map<String, Object> data = (Map<String, Object>) respMap.get("data");
            assertEquals("CALLING", data.get("status"));
            assertNotNull(data.get("roomId"), "roomId should be present");
            assertNotNull(data.get("token"), "callerToken should be present");
            assertEquals("ws://localhost:7880", data.get("sfuEndpoint"));
            log.info("Caller received CALLING ack: roomId={}", data.get("roomId"));

            // ── 验证被叫收到推送的 CALLING 消息 ──
            String calleePushMsg = calleeIn.poll(5, TimeUnit.SECONDS);
            assertNotNull(calleePushMsg, "Callee should receive a pushed message");
            Map<String, Object> pushMap = readJson(calleePushMsg);
            assertEquals("message", pushMap.get("op"));

            Map<String, Object> msgData = (Map<String, Object>) pushMap.get("data");
            assertEquals(callerId, msgData.get("fromUserId"));
            assertEquals(calleeId, msgData.get("toUserId"));
            assertEquals(5, msgData.get("contentType")); // ContentType.SIGNAL

            // 验证 content 中包含 CALLING action + calleeToken
            String contentStr = (String) msgData.get("content");
            assertNotNull(contentStr, "content should be present");
            assertTrue(contentStr.contains("\"action\":\"CALLING\""),
                    "should contain CALLING action, got: " + contentStr);
            assertTrue(contentStr.contains("\"roomId\""), "should contain roomId");
            assertTrue(contentStr.contains("\"token\""), "should contain calleeToken");
            log.info("Callee received CALLING push message with token");

        } finally {
            closeWs(caller);
            closeWs(callee);
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    void testInviteTimeout() throws Exception {
        BlockingQueue<String> callerIn = new LinkedBlockingQueue<>();
        BlockingQueue<String> calleeIn = new LinkedBlockingQueue<>();

        var caller = connectWs(callerIn);
        var callee = connectWs(calleeIn);

        try {
            // ── 注册 ──
            E2EUser callerUser = registerUser(caller, callerIn, "CallerTimeout");
            E2EUser calleeUser = registerUser(callee, calleeIn, "CalleeTimeout");
            String uid1 = callerUser.userId();
            String uid2 = calleeUser.userId();
            makeFriends(callerUser, calleeUser);

            // ── 登录获取 token ──
            String token1 = loginUser(caller, callerIn, callerUser, 1);
            loginUser(callee, calleeIn, calleeUser, 1);

            // ── 主叫 INVITE ──
            String inviteJson = "{\"op\":\"chat.send\",\"seq\":10,"
                    + "\"clientMsgId\":\"" + clientMsgId("call.timeout.invite") + "\","
                    + "\"toUserId\":\"" + uid2 + "\","
                    + "\"_ct\":\"signal\","
                    + "\"content\":{\"action\":\"INVITE\",\"sdp\":\"dummy\"},"
                    + "\"Authorization\":\"Bearer " + token1 + "\"}";
            String inviteResp = sendAndWait(caller, callerIn, inviteJson);
            assertNotNull(inviteResp, "INVITE response");
            assertEquals(0, readJson(inviteResp).get("code"));
            String roomId = (String) ((Map<String, Object>) readJson(inviteResp).get("data")).get("roomId");
            log.info("INVITE sent, roomId={}, waiting for timeout...", roomId);

            // 等 CALLING 推送到达再排掉（异步 MQ 投递，需等待）
            Thread.sleep(500);
            drainQueue(callerIn);
            drainQueue(calleeIn);

            // ── 等待超时（3s timeout + buffer）──
            String callerMsg = callerIn.poll(5, TimeUnit.SECONDS);
            String calleeMsg = calleeIn.poll(5, TimeUnit.SECONDS);

            // ── 验证双方收到 TIMEOUT ──
            assertNotNull(callerMsg, "Caller should receive TIMEOUT");
            Map<String, Object> callerPush = readJson(callerMsg);
            assertEquals("message", callerPush.get("op"));
            Map<String, Object> callerData = (Map<String, Object>) callerPush.get("data");
            assertEquals(5, callerData.get("contentType"));
            String callerContent = (String) callerData.get("content");
            assertTrue(callerContent.contains("\"action\":\"TIMEOUT\""), "caller should get TIMEOUT");

            assertNotNull(calleeMsg, "Callee should receive TIMEOUT");
            Map<String, Object> calleePush = readJson(calleeMsg);
            assertEquals("message", calleePush.get("op"));
            Map<String, Object> calleeData = (Map<String, Object>) calleePush.get("data");
            assertEquals(5, calleeData.get("contentType"));
            String calleeContent = (String) calleeData.get("content");
            assertTrue(calleeContent.contains("\"action\":\"TIMEOUT\""), "callee should get TIMEOUT");

            log.info("Both parties received TIMEOUT notifications");

        } finally {
            closeWs(caller);
            closeWs(callee);
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    void testInviteRejectedWhenParticipantBusy() throws Exception {
        BlockingQueue<String> callerIn = new LinkedBlockingQueue<>();
        BlockingQueue<String> calleeIn = new LinkedBlockingQueue<>();
        BlockingQueue<String> secondCallerIn = new LinkedBlockingQueue<>();

        var caller = connectWs(callerIn);
        var callee = connectWs(calleeIn);
        var secondCaller = connectWs(secondCallerIn);

        try {
            E2EUser callerUser = registerUser(caller, callerIn, "CallerBusy");
            E2EUser calleeUser = registerUser(callee, calleeIn, "CalleeBusy");
            E2EUser secondCallerUser = registerUser(secondCaller, secondCallerIn, "SecondCallerBusy");
            String uid1 = callerUser.userId();
            String uid2 = calleeUser.userId();
            String uid3 = secondCallerUser.userId();
            makeFriends(callerUser, calleeUser);
            makeFriends(secondCallerUser, calleeUser);

            String token1 = loginUser(caller, callerIn, callerUser, 1);
            loginUser(callee, calleeIn, calleeUser, 1);
            String token3 = loginUser(secondCaller, secondCallerIn, secondCallerUser, 1);

            String firstInvite = "{\"op\":\"chat.send\",\"seq\":10,"
                    + "\"clientMsgId\":\"" + clientMsgId("call.busy.first") + "\","
                    + "\"toUserId\":\"" + uid2 + "\","
                    + "\"_ct\":\"signal\","
                    + "\"content\":{\"action\":\"INVITE\",\"callType\":\"video\"},"
                    + "\"Authorization\":\"Bearer " + token1 + "\"}";
            Map<String, Object> firstResp = readJson(sendAndWait(caller, callerIn, firstInvite));
            assertEquals(0, firstResp.get("code"));
            String roomId = (String) ((Map<String, Object>) firstResp.get("data")).get("roomId");
            drainQueue(calleeIn);

            String secondInvite = "{\"op\":\"chat.send\",\"seq\":11,"
                    + "\"clientMsgId\":\"" + clientMsgId("call.busy.second") + "\","
                    + "\"toUserId\":\"" + uid2 + "\","
                    + "\"_ct\":\"signal\","
                    + "\"content\":{\"action\":\"INVITE\",\"callType\":\"voice\"},"
                    + "\"Authorization\":\"Bearer " + token3 + "\"}";
            Map<String, Object> busyResp = readJson(sendAndWait(secondCaller, secondCallerIn, secondInvite));
            assertEquals(409, busyResp.get("code"));
            assertEquals("call busy", busyResp.get("detail"));

            String cancel = "{\"op\":\"chat.send\",\"seq\":12,"
                    + "\"clientMsgId\":\"" + clientMsgId("call.busy.cancel") + "\","
                    + "\"toUserId\":\"" + uid2 + "\","
                    + "\"_ct\":\"signal\","
                    + "\"content\":{\"action\":\"CANCEL\",\"roomId\":\"" + roomId + "\"},"
                    + "\"Authorization\":\"Bearer " + token1 + "\"}";
            sendAndWait(caller, callerIn, cancel);
        } finally {
            closeWs(caller);
            closeWs(callee);
            closeWs(secondCaller);
        }
    }
}
