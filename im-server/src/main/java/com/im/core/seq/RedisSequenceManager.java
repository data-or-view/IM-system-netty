package com.im.core.seq;

import com.im.api.ISequenceManager;
import com.im.core.redis.RedisConfiguration;
import io.lettuce.core.cluster.api.async.RedisClusterAsyncCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

/**
 * Redis 序号管理器（生产环境用）。
 *
 * <p>利用 Redis INCR 命令的原子性保证每个 conversation 的 seq 独立递增。
 * 多节点并发安全，重启不丢失。</p>
 *
 * <p>Key 格式：{@code im:seq:{conversationId}}</p>
 *
 * <h3>降级策略</h3>
 * Redis 不可用时，nextSequence 降级返回 {@link System#currentTimeMillis()}，
 * 保证业务不中断（但会导致 seq 乱序，仅作为容错兜底）。
 */
public class RedisSequenceManager implements ISequenceManager {

    private static final Logger log = LoggerFactory.getLogger(RedisSequenceManager.class);

    private static final String KEY_PREFIX = "im:seq:";
    private static final long REDIS_TIMEOUT_MS = 3000;

    private final RedisClusterAsyncCommands<String, String> async;

    public RedisSequenceManager(RedisConfiguration redisConfig) {
        this.async = redisConfig.async();
        log.info("RedisSequenceManager initialized");
    }

    @Override
    public long nextSequence(String conversationId) {
        try {
            String key = KEY_PREFIX + conversationId;
            Long result = async.incr(key).get(REDIS_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (result == null) {
                log.error("Redis INCR returned null for key={}", key);
                return System.currentTimeMillis();
            }
            return result;
        } catch (Exception e) {
            log.error("Redis INCR failed for conversation {}: {} (fallback to timestamp)",
                    conversationId, e.getMessage());
            return System.currentTimeMillis();
        }
    }

    @Override
    public long getMaximumSequence(String conversationId) {
        try {
            String key = KEY_PREFIX + conversationId;
            String result = async.get(key).get(REDIS_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            return result != null ? Long.parseLong(result) : 0;
        } catch (Exception e) {
            log.warn("Redis GET failed for conversation {}: {} (return 0)",
                    conversationId, e.getMessage());
            return 0;
        }
    }
}
