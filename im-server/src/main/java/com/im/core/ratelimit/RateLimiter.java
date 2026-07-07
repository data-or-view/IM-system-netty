package com.im.core.ratelimit;

import java.time.Duration;

/**
 * Cluster-wide rate limit decision provider.
 */
@FunctionalInterface
public interface RateLimiter {

    RateLimitDecision check(String key, int limit, Duration window);
}
