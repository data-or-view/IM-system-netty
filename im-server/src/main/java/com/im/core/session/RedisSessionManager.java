package com.im.core.session;

import com.im.api.IConnectionSession;
import com.im.api.ISessionManager;
import com.im.api.MultiLoginStrategy;
import com.im.core.redis.RedisConfiguration;
import io.lettuce.core.cluster.api.async.RedisClusterAsyncCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Redis 增强的会话管理器（集群模式）。
 *
 * <p>继承 {@link SessionManager} 的本地 Channel → Session 追踪能力，
 * 增加 Redis 存储用于跨节点共享状态：</p>
 * <ul>
 *   <li>按用户的多端登录策略（{@code im:session:mls:{userId}}）</li>
 *   <li>本地强制登出（跨节点指令需后续扩展 {@code ClusterMessage} 的指令荷载）</li>
 * </ul>
 *
 * <p>核心会话操作（createSession / removeSession / bindUser / getByUserId 等）
 * 使用继承的本地 {@code ConcurrentHashMap}，因为 {@code Channel} 引用是 JVM 本地的。
 * 集群路由由 {@code DeliveryConsumer} + {@code RedisRouteTable} 处理。</p>
 */
public class RedisSessionManager extends SessionManager implements ISessionManager {

    private static final Logger log = LoggerFactory.getLogger(RedisSessionManager.class);

    private static final String KEY_MLS_PREFIX = "im:session:mls:";

    private final RedisClusterAsyncCommands<String, String> async;

    public RedisSessionManager(RedisConfiguration redisConfig) {
        this.async = redisConfig.async();
    }

    // ========================================
    //  多端登录策略（Redis 存储，跨节点一致）
    // ========================================

    @Override
    public MultiLoginStrategy getMultiLoginStrategy(String userId) {
        try {
            String val = async.get(KEY_MLS_PREFIX + userId).get();
            if (val != null) {
                return MultiLoginStrategy.valueOf(val);
            }
        } catch (Exception e) {
            log.warn("Failed to read multi-login strategy from Redis for user={}", userId, e);
        }
        return super.getLoginStrategy();
    }

    @Override
    public void setMultiLoginStrategy(String userId, MultiLoginStrategy strategy) {
        try {
            async.set(KEY_MLS_PREFIX + userId, strategy.name()).get();
            log.info("Multi-login strategy set in Redis: userId={}, strategy={}", userId, strategy);
        } catch (Exception e) {
            log.warn("Failed to set multi-login strategy in Redis for user={}", userId, e);
        }
    }

    // ========================================
    //  强制登出（本地 + 跨节点广播）
    // ========================================

    @Override
    public void forceLogout(String userId) {
        log.info("Force logout user={} on local node", userId);
        List<IConnectionSession> sessions = getSessionsByUserId(userId);
        sessions.forEach(s -> s.getConnection().close());
    }

    @Override
    public void forceLogout(String userId, int platformId) {
        log.info("Force logout user={} platform={} on local node", userId, platformId);
        List<IConnectionSession> sessions = getSessionsByUserId(userId);
        sessions.stream()
                .filter(s -> platformId == -1 || s.getPlatformId() == platformId)
                .forEach(s -> s.getConnection().close());
    }
}
