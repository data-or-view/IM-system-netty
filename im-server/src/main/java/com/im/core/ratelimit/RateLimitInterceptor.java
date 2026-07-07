package com.im.core.ratelimit;

import com.im.api.ApiInterceptor;
import com.im.api.ApiRequest;
import com.im.common.enums.ImErrorCode;
import com.im.common.exception.ImException;
import com.im.core.handler.unified.AuthInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
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
                throw rateLimited(rule, decision);
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
                log.warn("Rate limiter failed open: op={}, rule={}, key={}, error={}",
                        request.operation(), rule.name(), key, e.toString());
                return RateLimitDecision.allowed(0, 0, Duration.ZERO);
            }
            log.warn("Rate limiter failed closed: op={}, rule={}, key={}",
                    request.operation(), rule.name(), key, e);
            throw new ImException(ImErrorCode.RATE_LIMITED, "rate limiter unavailable")
                    .withAttribute("rateLimitRule", rule.name());
        }
    }

    private ImException rateLimited(RateLimitRule rule, RateLimitDecision decision) {
        return new ImException(ImErrorCode.RATE_LIMITED, "rate limit exceeded: " + rule.name())
                .withAttribute("rateLimitRule", rule.name())
                .withAttribute("retryAfterSeconds", retryAfterSeconds(decision.retryAfter()));
    }

    private long retryAfterSeconds(Duration retryAfter) {
        long millis = retryAfter.toMillis();
        if (millis <= 0) {
            return 0;
        }
        return (millis + 999) / 1000;
    }
}
