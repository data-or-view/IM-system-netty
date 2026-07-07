package com.im.bootstrap;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.http.WebSocket;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class FriendE2ETest extends BaseE2ETest {

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
    void friendListIncludesFriendDisplayProfile() throws Exception {
        BlockingQueue<String> aliceIn = new LinkedBlockingQueue<>();
        BlockingQueue<String> bobIn = new LinkedBlockingQueue<>();
        WebSocket aliceWs = connectWs(aliceIn);
        WebSocket bobWs = connectWs(bobIn);

        try {
            E2EUser alice = registerUser(aliceWs, aliceIn, "Alice Display");
            E2EUser bob = registerUser(bobWs, bobIn, "Bob Display");
            makeFriends(alice, bob);
            String aliceToken = loginUser(aliceWs, aliceIn, alice, 5);

            Map<String, Object> response = httpGet("/api/friend/list", aliceToken);
            assertEquals(0, response.get("code"), "friend list response: " + response);
            Map<String, Object> data = (Map<String, Object>) response.get("data");
            assertNotNull(data, "friend list data");
            List<Map<String, Object>> friends = (List<Map<String, Object>>) data.get("friends");
            Map<String, Object> bobInfo = friends.stream()
                    .filter(friend -> bob.userId().equals(friend.get("friendUserId")))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("Bob not found in Alice friend list: " + friends));

            assertEquals("Bob Display", bobInfo.get("nickname"));
        } finally {
            closeWs(aliceWs);
            closeWs(bobWs);
        }
    }
}
