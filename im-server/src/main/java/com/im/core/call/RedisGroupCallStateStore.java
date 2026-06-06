package com.im.core.call;

import com.im.core.redis.RedisConfiguration;
import io.lettuce.core.api.sync.RedisCommands;

import java.util.Map;

public class RedisGroupCallStateStore implements GroupCallStateStore {

    private static final String GROUP_KEY_PREFIX = "im:group_call:group:";
    private static final String MEMBER_KEY_PREFIX = "im:group_call:members:";
    private static final long DEFAULT_TTL_SECONDS = 12 * 60 * 60;

    private final RedisConfiguration redisConfig;
    private final long ttlSeconds;

    public RedisGroupCallStateStore(RedisConfiguration redisConfig) {
        this(redisConfig, DEFAULT_TTL_SECONDS);
    }

    RedisGroupCallStateStore(RedisConfiguration redisConfig, long ttlSeconds) {
        this.redisConfig = redisConfig;
        this.ttlSeconds = ttlSeconds;
    }

    @Override
    public GroupCallSession getActiveByGroup(String groupId) {
        try (RedisConfiguration.CloseableRedisCommands redis = redisConfig.createSyncCommands()) {
            RedisCommands<String, String> sync = redis.sync();
            return read(sync, groupId);
        }
    }

    @Override
    public GroupCallSession createIfAbsent(GroupCallSession session) {
        try (RedisConfiguration.CloseableRedisCommands redis = redisConfig.createSyncCommands()) {
            RedisCommands<String, String> sync = redis.sync();
            String key = groupKey(session.groupId());
            Boolean created = sync.hsetnx(key, "roomId", session.roomId());
            if (!Boolean.TRUE.equals(created)) {
                return read(sync, session.groupId());
            }
            sync.hset(key, Map.of(
                    "groupId", session.groupId(),
                    "callType", session.callType(),
                    "initiatorUserId", session.initiatorUserId(),
                    "sfuEndpoint", session.sfuEndpoint(),
                    "startedAt", String.valueOf(session.startedAt())
            ));
            sync.expire(key, ttlSeconds);
            sync.sadd(memberKey(session.groupId()), session.initiatorUserId());
            sync.expire(memberKey(session.groupId()), ttlSeconds);
            return read(sync, session.groupId());
        }
    }

    @Override
    public GroupCallSession addParticipant(String groupId, String userId) {
        try (RedisConfiguration.CloseableRedisCommands redis = redisConfig.createSyncCommands()) {
            RedisCommands<String, String> sync = redis.sync();
            if (!sync.exists(groupKey(groupId)).equals(1L)) return null;
            sync.sadd(memberKey(groupId), userId);
            sync.expire(memberKey(groupId), ttlSeconds);
            return read(sync, groupId);
        }
    }

    @Override
    public GroupCallSession removeParticipant(String groupId, String userId) {
        try (RedisConfiguration.CloseableRedisCommands redis = redisConfig.createSyncCommands()) {
            RedisCommands<String, String> sync = redis.sync();
            GroupCallSession before = read(sync, groupId);
            if (before == null) return null;
            sync.srem(memberKey(groupId), userId);
            Long count = sync.scard(memberKey(groupId));
            if (count == null || count == 0L) {
                sync.del(groupKey(groupId), memberKey(groupId));
                return before.withParticipantCount(0).markEnded();
            }
            return read(sync, groupId);
        }
    }

    @Override
    public GroupCallSession end(String groupId) {
        try (RedisConfiguration.CloseableRedisCommands redis = redisConfig.createSyncCommands()) {
            RedisCommands<String, String> sync = redis.sync();
            GroupCallSession before = read(sync, groupId);
            sync.del(groupKey(groupId), memberKey(groupId));
            return before != null ? before.markEnded() : null;
        }
    }

    private GroupCallSession read(RedisCommands<String, String> sync, String groupId) {
        Map<String, String> data = sync.hgetall(groupKey(groupId));
        if (data == null || data.isEmpty()) return null;
        Long participants = sync.scard(memberKey(groupId));
        return new GroupCallSession(
                data.get("groupId"),
                data.get("roomId"),
                data.get("callType"),
                data.get("initiatorUserId"),
                data.get("sfuEndpoint"),
                parseLong(data.get("startedAt")),
                participants != null ? participants.intValue() : 0,
                false);
    }

    private static long parseLong(String value) {
        if (value == null) return 0L;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private static String groupKey(String groupId) {
        return GROUP_KEY_PREFIX + groupId;
    }

    private static String memberKey(String groupId) {
        return MEMBER_KEY_PREFIX + groupId;
    }
}
