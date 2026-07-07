package com.im.core.ratelimit;

import com.im.api.ApiRequest;
import com.im.api.Operation;
import com.im.api.ResponseWriter;
import com.im.common.enums.ImErrorCode;
import com.im.common.exception.ImException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RateLimitInterceptorTest {

    @Test
    void allowedRequestChecksEveryPolicyRule() {
        RecordingLimiter limiter = new RecordingLimiter(RateLimitDecision.allowed(1, 9, Duration.ofSeconds(60)));
        RateLimitPolicy policy = RateLimitPolicy.defaults("im:rl:test:");
        RateLimitInterceptor interceptor = new RateLimitInterceptor(policy, limiter, false);
        ApiRequest request = authenticated(Operation.CHAT_SEND_GROUP, "u1", Map.of("groupId", "g1"));

        assertTrue(interceptor.preHandle(request));

        assertEquals(List.of(
                "im:rl:test:chat-send-group.user:user:u1",
                "im:rl:test:chat-send-group.user-group:user_group:u1:g1"
        ), limiter.keys);
    }

    @Test
    void rejectedRequestThrowsRateLimitedException() {
        RecordingLimiter limiter = new RecordingLimiter(RateLimitDecision.rejected(3, 0, Duration.ofSeconds(12)));
        RateLimitPolicy policy = RateLimitPolicy.defaults("im:rl:test:");
        RateLimitInterceptor interceptor = new RateLimitInterceptor(policy, limiter, false);
        ApiRequest request = authenticated(Operation.FRIEND_APPLY, "u1", Map.of("toUserId", "u2"));

        ImException error = assertThrows(ImException.class, () -> interceptor.preHandle(request));

        assertEquals(ImErrorCode.RATE_LIMITED, error.getErrorCode());
        assertEquals(12L, error.getAttributes().get("retryAfterSeconds"));
    }

    @Test
    void failOpenAllowsRequestWhenLimiterFails() {
        RateLimiter failingLimiter = (key, limit, window) -> {
            throw new IllegalStateException("redis unavailable");
        };
        RateLimitInterceptor interceptor = new RateLimitInterceptor(
                RateLimitPolicy.defaults("im:rl:test:"), failingLimiter, true);

        assertTrue(interceptor.preHandle(authenticated(Operation.CHAT_SEND, "u1", Map.of("toUserId", "u2"))));
    }

    private static ApiRequest authenticated(Operation operation, String userId, Map<String, Object> params) {
        ApiRequest request = new ApiRequest(operation, params, Map.of(), new NoopResponseWriter(), null);
        request.setAttribute(ApiRequest.ATTR_USER_ID, userId);
        request.setAttribute(ApiRequest.ATTR_CLIENT_IP, "203.0.113.10");
        return request;
    }

    private static final class RecordingLimiter implements RateLimiter {
        private final RateLimitDecision decision;
        private final List<String> keys = new ArrayList<>();

        private RecordingLimiter(RateLimitDecision decision) {
            this.decision = decision;
        }

        @Override
        public RateLimitDecision check(String key, int limit, Duration window) {
            keys.add(key);
            return decision;
        }
    }

    private static final class NoopResponseWriter implements ResponseWriter {
        @Override public void write(Object result) {}
        @Override public void writeError(ImErrorCode code, String detail) {}
    }
}
