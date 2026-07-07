package com.im.core.call;

import com.im.core.redis.CloseableRedisCommands;
import com.im.core.redis.RedisConfiguration;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.sync.RedisCommands;

import java.util.Map;

public class RedisSingleCallStateStore implements SingleCallStateStore {

    private static final String ROOM_KEY_PREFIX = "im:single_call:{state}:room:";
    private static final String USER_KEY_PREFIX = "im:single_call:{state}:user:";
    private static final long DEFAULT_TTL_SECONDS = 2 * 60 * 60;
    private static final String CREATE_IF_IDLE_SCRIPT = """
            if redis.call('exists', KEYS[2]) == 1 or redis.call('exists', KEYS[3]) == 1 then
              return 0
            end
            redis.call('hset', KEYS[1],
              'roomId', ARGV[1],
              'callerId', ARGV[2],
              'calleeId', ARGV[3],
              'callType', ARGV[4],
              'status', ARGV[5],
              'sfuEndpoint', ARGV[6],
              'startedAt', ARGV[7],
              'acceptedAt', ARGV[8])
            redis.call('set', KEYS[2], ARGV[1], 'EX', ARGV[9])
            redis.call('set', KEYS[3], ARGV[1], 'EX', ARGV[9])
            redis.call('expire', KEYS[1], ARGV[9])
            return 1
            """;
    private static final String TIMEOUT_IF_RINGING_SCRIPT = """
            if redis.call('hget', KEYS[1], 'status') ~= ARGV[1] then
              return 0
            end
            redis.call('del', KEYS[1], KEYS[2], KEYS[3])
            return 1
            """;
    private static final String ACCEPT_BY_PARTICIPANT_SCRIPT = """
            local caller = redis.call('hget', KEYS[1], 'callerId')
            local callee = redis.call('hget', KEYS[1], 'calleeId')
            if caller == false or callee == false then
              return 0
            end
            if ARGV[1] ~= caller and ARGV[1] ~= callee then
              return 0
            end
            redis.call('hset', KEYS[1], 'status', ARGV[2], 'acceptedAt', ARGV[3])
            redis.call('expire', KEYS[1], ARGV[4])
            redis.call('expire', KEYS[2], ARGV[4])
            redis.call('expire', KEYS[3], ARGV[4])
            return 1
            """;
    private static final String END_BY_PARTICIPANT_SCRIPT = """
            local caller = redis.call('hget', KEYS[1], 'callerId')
            local callee = redis.call('hget', KEYS[1], 'calleeId')
            if caller == false or callee == false then
              return 0
            end
            if ARGV[1] ~= caller and ARGV[1] ~= callee then
              return 0
            end
            redis.call('del', KEYS[1], KEYS[2], KEYS[3])
            return 1
            """;

    private final RedisConfiguration redisConfig;
    private final long ttlSeconds;

    public RedisSingleCallStateStore(RedisConfiguration redisConfig) {
        this(redisConfig, DEFAULT_TTL_SECONDS);
    }

    RedisSingleCallStateStore(RedisConfiguration redisConfig, long ttlSeconds) {
        this.redisConfig = redisConfig;
        this.ttlSeconds = ttlSeconds;
    }

    @Override
    public SingleCallSession getByRoom(String roomId) {
        if (!hasText(roomId)) return null;
        try (CloseableRedisCommands redis = redisConfig.createSyncCommands()) {
            RedisCommands<String, String> sync = redis.sync();
            return read(sync, roomId);
        }
    }

    @Override
    public SingleCallSession getActiveByUser(String userId) {
        if (!hasText(userId)) return null;
        try (CloseableRedisCommands redis = redisConfig.createSyncCommands()) {
            RedisCommands<String, String> sync = redis.sync();
            String roomId = sync.get(userKey(userId));
            return hasText(roomId) ? read(sync, roomId) : null;
        }
    }

    @Override
    public SingleCallSession createIfUsersIdle(SingleCallSession session) {
        try (CloseableRedisCommands redis = redisConfig.createSyncCommands()) {
            RedisCommands<String, String> sync = redis.sync();
            Long created = sync.eval(CREATE_IF_IDLE_SCRIPT, ScriptOutputType.INTEGER,
                    new String[]{roomKey(session.roomId()), userKey(session.callerId()), userKey(session.calleeId())},
                    session.roomId(),
                    session.callerId(),
                    session.calleeId(),
                    session.callType(),
                    session.status(),
                    session.sfuEndpoint(),
                    String.valueOf(session.startedAt()),
                    String.valueOf(session.acceptedAt()),
                    String.valueOf(ttlSeconds));
            return Long.valueOf(1L).equals(created) ? read(sync, session.roomId()) : null;
        }
    }

    @Override
    public SingleCallSession accept(String roomId) {
        if (!hasText(roomId)) return null;
        try (CloseableRedisCommands redis = redisConfig.createSyncCommands()) {
            RedisCommands<String, String> sync = redis.sync();
            SingleCallSession session = read(sync, roomId);
            if (session == null) return null;
            long now = System.currentTimeMillis();
            sync.hset(roomKey(roomId), Map.of(
                    "status", SingleCallSession.STATUS_ACCEPTED,
                    "acceptedAt", String.valueOf(now)));
            sync.expire(roomKey(roomId), ttlSeconds);
            sync.expire(userKey(session.callerId()), ttlSeconds);
            sync.expire(userKey(session.calleeId()), ttlSeconds);
            return read(sync, roomId);
        }
    }

    @Override
    public SingleCallSession acceptBy(String roomId, String actorId) {
        if (!hasText(roomId) || !hasText(actorId)) return null;
        try (CloseableRedisCommands redis = redisConfig.createSyncCommands()) {
            RedisCommands<String, String> sync = redis.sync();
            SingleCallSession before = read(sync, roomId);
            if (before == null) return null;
            long now = System.currentTimeMillis();
            Long accepted = sync.eval(ACCEPT_BY_PARTICIPANT_SCRIPT, ScriptOutputType.INTEGER,
                    new String[]{roomKey(roomId), userKey(before.callerId()), userKey(before.calleeId())},
                    actorId,
                    SingleCallSession.STATUS_ACCEPTED,
                    String.valueOf(now),
                    String.valueOf(ttlSeconds));
            return Long.valueOf(1L).equals(accepted) ? read(sync, roomId) : null;
        }
    }

    @Override
    public SingleCallSession timeoutIfRinging(String roomId) {
        if (!hasText(roomId)) return null;
        try (CloseableRedisCommands redis = redisConfig.createSyncCommands()) {
            RedisCommands<String, String> sync = redis.sync();
            SingleCallSession session = read(sync, roomId);
            if (session == null) return null;
            Long removed = sync.eval(TIMEOUT_IF_RINGING_SCRIPT, ScriptOutputType.INTEGER,
                    new String[]{roomKey(roomId), userKey(session.callerId()), userKey(session.calleeId())},
                    SingleCallSession.STATUS_RINGING);
            return Long.valueOf(1L).equals(removed) ? session.end() : null;
        }
    }

    @Override
    public SingleCallSession end(String roomId) {
        if (!hasText(roomId)) return null;
        try (CloseableRedisCommands redis = redisConfig.createSyncCommands()) {
            RedisCommands<String, String> sync = redis.sync();
            SingleCallSession session = read(sync, roomId);
            if (session == null) return null;
            sync.del(roomKey(roomId), userKey(session.callerId()), userKey(session.calleeId()));
            return session.end();
        }
    }

    @Override
    public SingleCallSession endBy(String roomId, String actorId) {
        if (!hasText(roomId) || !hasText(actorId)) return null;
        try (CloseableRedisCommands redis = redisConfig.createSyncCommands()) {
            RedisCommands<String, String> sync = redis.sync();
            SingleCallSession session = read(sync, roomId);
            if (session == null) return null;
            Long ended = sync.eval(END_BY_PARTICIPANT_SCRIPT, ScriptOutputType.INTEGER,
                    new String[]{roomKey(roomId), userKey(session.callerId()), userKey(session.calleeId())},
                    actorId);
            return Long.valueOf(1L).equals(ended) ? session.end() : null;
        }
    }

    private SingleCallSession read(RedisCommands<String, String> sync, String roomId) {
        Map<String, String> data = sync.hgetall(roomKey(roomId));
        if (data == null || data.isEmpty()) return null;
        return new SingleCallSession(
                data.get("roomId"),
                data.get("callerId"),
                data.get("calleeId"),
                data.getOrDefault("callType", "voice"),
                data.getOrDefault("status", SingleCallSession.STATUS_RINGING),
                data.get("sfuEndpoint"),
                parseLong(data.get("startedAt")),
                parseLong(data.get("acceptedAt")));
    }

    private static long parseLong(String value) {
        if (value == null) return 0L;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String roomKey(String roomId) {
        return ROOM_KEY_PREFIX + roomId;
    }

    private static String userKey(String userId) {
        return USER_KEY_PREFIX + userId;
    }
}
