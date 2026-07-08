package com.im.core.ratelimit;

import com.im.api.ApiInterceptor;
import com.im.api.ApiRequest;
import com.im.common.enums.ImErrorCode;
import com.im.common.exception.ImException;
import com.im.core.handler.unified.AuthInterceptor;
import com.im.core.observability.LogEvents;
import com.im.core.observability.LogFields;
import com.im.core.observability.RequestObservability;
import com.im.core.observability.StructuredLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Applies cluster-wide rate limits after authentication has populated user context.
 */
public final class RateLimitInterceptor implements ApiInterceptor {

    public static final int ORDER = AuthInterceptor.ORDER + 100;

    private static final Logger log = LoggerFactory.getLogger(RateLimitInterceptor.class);

    private final RateLimitPolicy policy;
    private final RateLimiter limiter;
    private final boolean failOpen;

    public RateLimitInterceptor(RateLimitPolicy policy, RateLimiter limiter, boolean failOpen) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.limiter = Objects.requireNonNull(limiter, "limiter");
        this.failOpen = failOpen;
    }

    @Override
    public String name() {
        return "rate-limit";
    }

    @Override
    public int order() {
        return ORDER;
    }

    @Override
    public boolean preHandle(ApiRequest request) {
        for (RateLimitRule rule : policy.rulesFor(request)) {
            RateLimitDecision decision = check(rule, request);
            if (!decision.allowed()) {
                throw rateLimited(rule, decision, request);
            }
        }
        return true;
    }

    private RateLimitDecision check(RateLimitRule rule, ApiRequest request) {
        String key = rule.key(request);
        try {
            return limiter.check(key, rule.limit(), rule.window());
        } catch (Exception e) {
            if (failOpen) {
                Map<String, Object> fields = commonFields(rule, request, key, null);
                fields.put(LogFields.FAIL_OPEN, true);
                fields.put(LogFields.EXCEPTION_CLASS, e.getClass().getSimpleName());
                log.warn(StructuredLog.event(LogEvents.RATE_LIMIT_BACKEND_FAILED,
                        fields));
                return RateLimitDecision.allowed(0, 0, Duration.ZERO);
            }
            Map<String, Object> fields = commonFields(rule, request, key, null);
            fields.put(LogFields.FAIL_OPEN, false);
            fields.put(LogFields.EXCEPTION_CLASS, e.getClass().getSimpleName());
            log.warn(StructuredLog.event(LogEvents.RATE_LIMIT_BACKEND_FAILED,
                    fields), e);
            throw new ImException(ImErrorCode.RATE_LIMITED, "rate limiter unavailable")
                    .withAttribute("rateLimitRule", rule.name());
        }
    }

    private ImException rateLimited(RateLimitRule rule, RateLimitDecision decision, ApiRequest request) {
        Map<String, Object> fields = commonFields(rule, request, rule.key(request), decision);
        log.warn(StructuredLog.event(LogEvents.RATE_LIMIT_REJECTED, fields));
        return new ImException(ImErrorCode.RATE_LIMITED, "rate limit exceeded: " + rule.name())
                .withAttribute("rateLimitRule", rule.name())
                .withAttribute("retryAfterSeconds", retryAfterSeconds(decision.retryAfter()));
    }

    private Map<String, Object> commonFields(RateLimitRule rule, ApiRequest request, String key,
                                             RateLimitDecision decision) {
        Map<String, Object> fields = new LinkedHashMap<>();
        if (request != null) {
            fields.put(LogFields.REQUEST_ID, RequestObservability.requestId(request));
            fields.put(LogFields.TRACE_ID, RequestObservability.traceId(request));
            fields.put(LogFields.USER_ID, RequestObservability.userId(request));
            fields.put(LogFields.OPERATION, request.operation());
            fields.put(LogFields.PROTOCOL, RequestObservability.protocol(request));
            fields.put(LogFields.CLIENT_IP, RequestObservability.clientIp(request));
        }
        fields.put(LogFields.RULE, rule.name());
        fields.put(LogFields.KEY, key);
        fields.put(LogFields.LIMIT, rule.limit());
        fields.put(LogFields.WINDOW_MS, rule.window().toMillis());
        if (decision != null) {
            fields.put(LogFields.CURRENT_COUNT, decision.currentCount());
            fields.put(LogFields.REMAINING, decision.remaining());
            fields.put(LogFields.RETRY_AFTER_SECONDS, retryAfterSeconds(decision.retryAfter()));
        }
        return fields;
    }

    private long retryAfterSeconds(Duration retryAfter) {
        long millis = retryAfter.toMillis();
        if (millis <= 0) {
            return 0;
        }
        return (millis + 999) / 1000;
    }
}
