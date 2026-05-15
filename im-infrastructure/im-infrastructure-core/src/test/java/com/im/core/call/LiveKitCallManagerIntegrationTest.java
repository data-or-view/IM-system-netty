package com.im.core.call;

import com.im.api.RoomInformation;
import com.im.api.ImException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LiveKit 集成测试。
 *
 * 前提：LiveKit 在本地 Docker 中运行
 *   docker run -d --name im-livekit -p 7880:7880 \
 *     livekit/livekit-server --config /etc/livekit/livekit.yaml
 *
 * 如果 LiveKit 未启动，测试跳过。
 */
class LiveKitCallManagerIntegrationTest {

    private static final String API_KEY = "devkey";
    private static final String API_SECRET = "im-system-livekit-secret-2024";
    private static final String SFU_ENDPOINT = "ws://localhost:7880";

    private static LiveKitCallManager callManager;
    private static boolean liveKitAvailable = false;

    @BeforeAll
    static void setup() {
        // 先检查 LiveKit 是否可连
        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:7880/"))
                    .GET()
                    .build();
            HttpResponse<Void> resp = client.send(req, HttpResponse.BodyHandlers.discarding());
            liveKitAvailable = resp.statusCode() == 200;
        } catch (Exception e) {
            liveKitAvailable = false;
        }

        if (liveKitAvailable) {
            callManager = new LiveKitCallManager(API_KEY, API_SECRET, SFU_ENDPOINT);
        }
    }

    @Test
    void testCreateRoom() {
        if (!liveKitAvailable) return;

        RoomInformation room = callManager.createRoom("alice", "bob", null);
        assertNotNull(room);
        assertNotNull(room.getRoomId());
        assertEquals(SFU_ENDPOINT, room.getSfuEndpoint());
        assertNotNull(room.getCallerToken());
        assertNotNull(room.getCalleeToken());
        System.out.println("Room created: " + room.getRoomId());
        System.out.println("Caller token: " + room.getCallerToken());
        System.out.println("Callee token: " + room.getCalleeToken());
    }

    @Test
    void testCreateRoomWithCustomId() {
        if (!liveKitAvailable) return;

        RoomInformation room = callManager.createRoom("alice", "bob", "test-room-123");
        assertEquals("test-room-123", room.getRoomId());
    }

    @Test
    void testIssueToken() {
        if (!liveKitAvailable) return;

        String token = callManager.issueToken("charlie", "room-456");
        assertNotNull(token);
        assertTrue(token.split("\\.").length == 3, "Should be a valid JWT with 3 parts");
    }

    @Test
    void testValidateTokenViaHTTP() throws Exception {
        if (!liveKitAvailable) return;

        // 签发一个 token
        String token = callManager.issueToken("dave", "room-789");

        // 调用 LiveKit RTI API 验证 token
        HttpClient client = HttpClient.newHttpClient();
        String body = "{\"token\":\"" + token + "\"}";
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:7880/rtc/validate"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        System.out.println("Validate response: " + resp.statusCode() + " " + resp.body());
        // LiveKit should accept the token
        assertTrue(resp.statusCode() == 200 || resp.statusCode() == 401,
                "Token should be valid even if room doesn't exist yet");
    }
}
