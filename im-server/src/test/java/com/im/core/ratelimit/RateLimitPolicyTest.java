package com.im.core.ratelimit;

import com.im.api.ApiRequest;
import com.im.api.Operation;
import com.im.api.ResponseWriter;
import com.im.common.enums.ImErrorCode;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RateLimitPolicyTest {

    @Test
    void loginUsesIpAndSubmittedUserIdRules() {
        RateLimitPolicy policy = RateLimitPolicy.defaults("im:rl:test:");
        ApiRequest request = request(Operation.LOGIN, Map.of("userId", "alice"));
        request.setAttribute(ApiRequest.ATTR_CLIENT_IP, "203.0.113.10");

        List<RateLimitRule> rules = policy.rulesFor(request);

        assertEquals(2, rules.size());
        assertTrue(rules.stream().anyMatch(rule ->
                rule.name().equals("login.ip") &&
                rule.key(request).equals("im:rl:test:login.ip:ip:203.0.113.10")));
        assertTrue(rules.stream().anyMatch(rule ->
                rule.name().equals("login.user") &&
                rule.key(request).equals("im:rl:test:login.user:login_user:alice")));
    }

    @Test
    void highRiskOperationsUseResourceScopedRules() {
        RateLimitPolicy policy = RateLimitPolicy.defaults("im:rl:test:");
        ApiRequest friendApply = authenticated(Operation.FRIEND_APPLY, "u1", Map.of("toUserId", "u2"));
        ApiRequest groupJoin = authenticated(Operation.GROUP_JOIN, "u1", Map.of("groupId", "g1"));
        ApiRequest groupMessage = authenticated(Operation.CHAT_SEND_GROUP, "u1", Map.of("groupId", "g1"));
        ApiRequest systemPublish = authenticated(Operation.ADMIN_SYSTEM_MESSAGE_PUBLISH, "admin", Map.of());

        assertTrue(policy.rulesFor(friendApply).stream()
                .anyMatch(rule -> rule.key(friendApply).endsWith("user_target:u1:u2")));
        assertTrue(policy.rulesFor(groupJoin).stream()
                .anyMatch(rule -> rule.key(groupJoin).endsWith("user_group:u1:g1")));
        assertTrue(policy.rulesFor(groupMessage).stream()
                .anyMatch(rule -> rule.key(groupMessage).endsWith("user_group:u1:g1")));
        assertTrue(policy.rulesFor(systemPublish).stream()
                .anyMatch(rule -> rule.key(systemPublish).endsWith("global:global")));
    }

    @Test
    void fileUploadMutationFlowUsesUploadRule() {
        RateLimitPolicy policy = RateLimitPolicy.defaults("im:rl:test:");

        assertTrue(policy.rulesFor(authenticated(Operation.FILE_UPLOAD_COMPLETE, "u1", Map.of())).stream()
                .anyMatch(rule -> rule.name().equals("file-upload.user")));
        assertTrue(policy.rulesFor(authenticated(Operation.FILE_MULTIPART_ABORT, "u1", Map.of())).stream()
                .anyMatch(rule -> rule.name().equals("file-upload.user")));
    }

    @Test
    void configCanOverrideDefaultLimitsWithoutChangingCode() {
        TestConfig config = new TestConfig(Map.of(
                "im.rate-limit.key-prefix", "im:rl:override:",
                "im.rate-limit.login.ip.limit", "3",
                "im.rate-limit.login.ip.window-seconds", "15"
        ));

        RateLimitPolicy policy = RateLimitPolicy.fromConfig(config);
        RateLimitRule loginIp = policy.rulesFor(request(Operation.LOGIN, Map.of()))
                .stream()
                .filter(rule -> rule.name().equals("login.ip"))
                .findFirst()
                .orElseThrow();

        assertEquals(3, loginIp.limit());
        assertEquals(Duration.ofSeconds(15), loginIp.window());
        assertEquals("im:rl:override:login.ip:ip:unknown", loginIp.key(request(Operation.LOGIN, Map.of())));
    }

    @Test
    void untrustedIdentityPartsAreHashedBeforeBuildingRedisKey() {
        RateLimitPolicy policy = RateLimitPolicy.defaults("im:rl:test:");
        String longAccount = "alice\n" + "x".repeat(200);
        ApiRequest request = request(Operation.LOGIN, Map.of("userId", longAccount));

        String key = policy.rulesFor(request)
                .stream()
                .filter(rule -> rule.name().equals("login.user"))
                .findFirst()
                .orElseThrow()
                .key(request);

        assertTrue(key.startsWith("im:rl:test:login.user:login_user:sha256:"));
        assertEquals("im:rl:test:login.user:login_user:sha256:".length() + 64, key.length());
    }

    private static ApiRequest authenticated(Operation operation, String userId, Map<String, Object> params) {
        ApiRequest request = request(operation, params);
        request.setAttribute(ApiRequest.ATTR_USER_ID, userId);
        request.setAttribute(ApiRequest.ATTR_CLIENT_IP, "203.0.113.10");
        return request;
    }

    private static ApiRequest request(Operation operation, Map<String, Object> params) {
        return new ApiRequest(operation, params, Map.of(), new NoopResponseWriter(), null);
    }

    private record TestConfig(Map<String, String> values) implements com.im.config.Config {
        @Override public java.util.Optional<String> getString(String key) { return java.util.Optional.ofNullable(values.get(key)); }
        @Override public java.util.Optional<Integer> getInt(String key) { return java.util.Optional.ofNullable(values.get(key)).map(Integer::parseInt); }
        @Override public java.util.Optional<Long> getLong(String key) { return java.util.Optional.ofNullable(values.get(key)).map(Long::parseLong); }
        @Override public java.util.Optional<Boolean> getBoolean(String key) { return java.util.Optional.ofNullable(values.get(key)).map(Boolean::parseBoolean); }
        @Override public java.util.Optional<Duration> getDuration(String key) { return java.util.Optional.empty(); }
        @Override public boolean hasKey(String key) { return values.containsKey(key); }
    }

    private static final class NoopResponseWriter implements ResponseWriter {
        @Override public void write(Object result) {}
        @Override public void writeError(ImErrorCode code, String detail) {}
    }
}
