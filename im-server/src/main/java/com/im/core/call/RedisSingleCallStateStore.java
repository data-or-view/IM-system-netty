package com.im.core.call;

import com.im.core.redis.CloseableRedisCommands;
import com.im.core.redis.RedisConfiguration;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.cluster.api.sync.RedisClusterCommands;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class RedisSingleCallStateStore implements SingleCallStateStore {

    private static final String ROOM_KEY_PREFIX = "im:single_call:{state}:room:";
    private static final String USER_KEY_PREFIX = "im:single_call:{state}:user:";
    private static final String DEADLINE_KEY = "im:single_call:{state}:deadlines";
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
              'acceptedAt', ARGV[8],
              'deadlineAt', ARGV[9])
            redis.call('set', KEYS[2], ARGV[1], 'EX', ARGV[10])
            redis.call('set', KEYS[3], ARGV[1], 'EX', ARGV[10])
            redis.call('expire', KEYS[1], ARGV[10])
            if ARGV[5] == 'RINGING' then
              redis.call('zadd', KEYS[4], ARGV[9], ARGV[1])
              redis.call('expire', KEYS[4], ARGV[10])
            end
            return 1
            """;
    private static final String TIMEOUT_IF_RINGING_SCRIPT = """
            if redis.call('hget', KEYS[1], 'status') ~= ARGV[1] then
              return 0
            end
            redis.call('zrem', KEYS[4], ARGV[2])
            redis.call('del', KEYS[2], KEYS[3])
            redis.call('hset', KEYS[1], 'status', ARGV[3])
            redis.call('expire', KEYS[1], ARGV[4])
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
            if redis.call('hget', KEYS[1], 'status') ~= ARGV[2] then
              return 0
            end
            redis.call('zrem', KEYS[4], ARGV[3])
            redis.call('hset', KEYS[1], 'status', ARGV[4], 'acceptedAt', ARGV[5])
            redis.call('expire', KEYS[1], ARGV[6])
            redis.call('expire', KEYS[2], ARGV[6])
            redis.call('expire', KEYS[3], ARGV[6])
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
            redis.call('zrem', KEYS[4], ARGV[2])
            redis.call('del', KEYS[1], KEYS[2], KEYS[3])
            return 1
            """;
    private static final String END_SCRIPT = """
            local caller = redis.call('hget', KEYS[1], 'callerId')
            local callee = redis.call('hget', KEYS[1], 'calleeId')
            if caller == false or callee == false then
              return 0
            end
            redis.call('zrem', KEYS[4], ARGV[1])
            redis.call('del', KEYS[1], KEYS[2], KEYS[3])
            return 1
            """;
    private static final String CLAIM_EXPIRED_RINGING_SCRIPT = """
            local roomIds = redis.call('zrangebyscore', KEYS[1], '-inf', ARGV[1], 'LIMIT', 0, ARGV[2])
            local claimed = {}
            for _, roomId in ipairs(roomIds) do
              local roomKey = ARGV[3] .. roomId
              if redis.call('hget', roomKey, 'status') == ARGV[4] then
                local caller = redis.call('hget', roomKey, 'callerId')
                local callee = redis.call('hget', roomKey, 'calleeId')
                redis.call('zrem', KEYS[1], roomId)
                if caller ~= false then redis.call('del', ARGV[5] .. caller) end
                if callee ~= false then redis.call('del', ARGV[5] .. callee) end
                redis.call('hset', roomKey, 'status', ARGV[6])
                redis.call('expire', roomKey, ARGV[7])
                table.insert(claimed, roomId)
              else
                redis.call('zrem', KEYS[1], roomId)
              end
            end
            return claimed
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
            RedisClusterCommands<String, String> sync = redis.sync();
            return read(sync, roomId);
        }
    }

    @Override
    public SingleCallSession getActiveByUser(String userId) {
        if (!hasText(userId)) return null;
        try (CloseableRedisCommands redis = redisConfig.createSyncCommands()) {
            RedisClusterCommands<String, String> sync = redis.sync();
            String roomId = sync.get(userKey(userId));
            return hasText(roomId) ? read(sync, roomId) : null;
        }
    }

    @Override
    public SingleCallSession createIfUsersIdle(SingleCallSession session) {
        try (CloseableRedisCommands redis = redisConfig.createSyncCommands()) {
            RedisClusterCommands<String, String> sync = redis.sync();
            Long created = sync.eval(CREATE_IF_IDLE_SCRIPT, ScriptOutputType.INTEGER,
                    new String[]{roomKey(session.roomId()), userKey(session.callerId()), userKey(session.calleeId()), deadlineKey()},
                    session.roomId(),
                    session.callerId(),
                    session.calleeId(),
                    session.callType(),
                    session.status(),
                    session.sfuEndpoint() == null ? "" : session.sfuEndpoint(),
                    String.valueOf(session.startedAt()),
                    String.valueOf(session.acceptedAt()),
                    String.valueOf(session.deadlineAt()),
                    String.valueOf(ttlSeconds));
            return Long.valueOf(1L).equals(created) ? read(sync, session.roomId()) : null;
        }
    }

    @Override
    public SingleCallSession accept(String roomId) {
        if (!hasText(roomId)) return null;
        try (CloseableRedisCommands redis = redisConfig.createSyncCommands()) {
            RedisClusterCommands<String, String> sync = redis.sync();
            SingleCallSession session = read(sync, roomId);
            if (session == null) return null;
            long now = System.currentTimeMillis();
            Long accepted = sync.eval(ACCEPT_BY_PARTICIPANT_SCRIPT, ScriptOutputType.INTEGER,
                    callKeys(session), session.callerId(), SingleCallSession.STATUS_RINGING,
                    session.roomId(), SingleCallSession.STATUS_ACCEPTED, String.valueOf(now), String.valueOf(ttlSeconds));
            return Long.valueOf(1L).equals(accepted) ? read(sync, roomId) : null;
        }
    }

    @Override
    public SingleCallSession acceptBy(String roomId, String actorId) {
        if (!hasText(roomId) || !hasText(actorId)) return null;
        try (CloseableRedisCommands redis = redisConfig.createSyncCommands()) {
            RedisClusterCommands<String, String> sync = redis.sync();
            SingleCallSession before = read(sync, roomId);
            if (before == null) return null;
            long now = System.currentTimeMillis();
            Long accepted = sync.eval(ACCEPT_BY_PARTICIPANT_SCRIPT, ScriptOutputType.INTEGER,
                    callKeys(before),
                    actorId,
                    SingleCallSession.STATUS_RINGING,
                    before.roomId(),
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
            RedisClusterCommands<String, String> sync = redis.sync();
            SingleCallSession session = read(sync, roomId);
            if (session == null) return null;
            Long removed = sync.eval(TIMEOUT_IF_RINGING_SCRIPT, ScriptOutputType.INTEGER,
                    callKeys(session), SingleCallSession.STATUS_RINGING, session.roomId(),
                    SingleCallSession.STATUS_TIMED_OUT, String.valueOf(ttlSeconds));
            return Long.valueOf(1L).equals(removed) ? session.timedOut() : null;
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<SingleCallSession> claimExpiredRinging(long nowEpochMillis, int limit) {
        if (limit <= 0) return List.of();
        try (CloseableRedisCommands redis = redisConfig.createSyncCommands()) {
            RedisClusterCommands<String, String> sync = redis.sync();
            List<Object> claimedRoomIds = sync.eval(CLAIM_EXPIRED_RINGING_SCRIPT, ScriptOutputType.MULTI,
                    new String[]{deadlineKey()}, String.valueOf(nowEpochMillis), String.valueOf(limit),
                    ROOM_KEY_PREFIX, SingleCallSession.STATUS_RINGING, USER_KEY_PREFIX,
                    SingleCallSession.STATUS_TIMED_OUT, String.valueOf(ttlSeconds));
            if (claimedRoomIds == null || claimedRoomIds.isEmpty()) return List.of();
            List<SingleCallSession> claimed = new ArrayList<>(claimedRoomIds.size());
            for (Object roomId : claimedRoomIds) {
                SingleCallSession session = read(sync, String.valueOf(roomId));
                if (session != null) claimed.add(session);
            }
            return claimed;
        }
    }

    @Override
    public SingleCallSession end(String roomId) {
        if (!hasText(roomId)) return null;
        try (CloseableRedisCommands redis = redisConfig.createSyncCommands()) {
            RedisClusterCommands<String, String> sync = redis.sync();
            SingleCallSession session = read(sync, roomId);
            if (session == null) return null;
            Long ended = sync.eval(END_SCRIPT, ScriptOutputType.INTEGER, callKeys(session), session.roomId());
            return Long.valueOf(1L).equals(ended) ? session.end() : null;
        }
    }

    @Override
    public SingleCallSession endBy(String roomId, String actorId) {
        if (!hasText(roomId) || !hasText(actorId)) return null;
        try (CloseableRedisCommands redis = redisConfig.createSyncCommands()) {
            RedisClusterCommands<String, String> sync = redis.sync();
            SingleCallSession session = read(sync, roomId);
            if (session == null) return null;
            Long ended = sync.eval(END_BY_PARTICIPANT_SCRIPT, ScriptOutputType.INTEGER,
                    callKeys(session), actorId, session.roomId());
            return Long.valueOf(1L).equals(ended) ? session.end() : null;
        }
    }

    private SingleCallSession read(RedisClusterCommands<String, String> sync, String roomId) {
        Map<String, String> data = sync.hgetall(roomKey(roomId));
        if (data == null || data.isEmpty()) return null;
        return new SingleCallSession(
                data.get("roomId"),
                data.get("callerId"),
                data.get("calleeId"),
                data.getOrDefault("callType", "voice"),
                data.getOrDefault("status", SingleCallSession.STATUS_RINGING),
                emptyToNull(data.get("sfuEndpoint")),
                parseLong(data.get("startedAt")),
                parseLong(data.get("acceptedAt")),
                parseLong(data.get("deadlineAt")));
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

    private static String deadlineKey() {
        return DEADLINE_KEY;
    }

    private static String[] callKeys(SingleCallSession session) {
        return new String[]{roomKey(session.roomId()), userKey(session.callerId()), userKey(session.calleeId()), deadlineKey()};
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }
}
