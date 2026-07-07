package com.im.core.ratelimit;

import com.im.core.redis.RedisConfiguration;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.cluster.api.async.RedisClusterAsyncCommands;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Redis-backed fixed-window limiter using one Lua script per key.
 */
public final class RedisRateLimiter implements RateLimiter {

    private static final String SCRIPT = """
            local current = redis.call('INCR', KEYS[1])
            if current == 1 then
              redis.call('PEXPIRE', KEYS[1], ARGV[2])
            end
            local ttl = redis.call('PTTL', KEYS[1])
            if ttl < 0 then
              redis.call('PEXPIRE', KEYS[1], ARGV[2])
              ttl = tonumber(ARGV[2])
            end
            local allowed = 0
            if current <= tonumber(ARGV[1]) then
              allowed = 1
            end
            local remaining = tonumber(ARGV[1]) - current
            if remaining < 0 then
              remaining = 0
            end
            return {allowed, current, remaining, ttl}
            """;

    private final RedisClusterAsyncCommands<String, String> commands;

    public RedisRateLimiter(RedisConfiguration redisConfig) {
        this(Objects.requireNonNull(redisConfig, "redisConfig").async());
    }

    RedisRateLimiter(RedisClusterAsyncCommands<String, String> commands) {
        this.commands = Objects.requireNonNull(commands, "commands");
    }

    @Override
    public RateLimitDecision check(String key, int limit, Duration window) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(window, "window");
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        if (window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("window must be positive");
        }

        RedisFuture<List<Object>> future = commands.eval(
                SCRIPT,
                ScriptOutputType.MULTI,
                new String[]{key},
                String.valueOf(limit),
                String.valueOf(window.toMillis()));
        return toDecision(future.toCompletableFuture().join());
    }

    static RateLimitDecision toDecision(List<?> reply) {
        if (reply == null || reply.size() < 4) {
            throw new IllegalStateException("invalid redis rate limit reply");
        }
        boolean allowed = number(reply.get(0)) == 1L;
        long currentCount = number(reply.get(1));
        long remaining = Math.max(number(reply.get(2)), 0L);
        Duration retryAfter = Duration.ofMillis(Math.max(number(reply.get(3)), 0L));
        return allowed
                ? RateLimitDecision.allowed(currentCount, remaining, retryAfter)
                : RateLimitDecision.rejected(currentCount, remaining, retryAfter);
    }

    private static long number(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text) {
            return Long.parseLong(text);
        }
        throw new IllegalStateException("invalid redis rate limit number: " + value);
    }
}
