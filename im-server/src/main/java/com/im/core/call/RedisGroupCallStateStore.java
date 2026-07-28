package com.im.core.call;

import com.im.core.redis.CloseableRedisCommands;
import com.im.core.redis.RedisConfiguration;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.sync.RedisCommands;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Redis-backed group-call state. Every membership transition spans both hashes in one Lua script. */
public class RedisGroupCallStateStore implements GroupCallStateStore {

    private static final String GROUP_KEY_PREFIX = "im:group_call:{state}:group:";
    private static final String MEMBER_KEY_PREFIX = "im:group_call:{state}:members:";
    private static final long DEFAULT_TTL_SECONDS = 12 * 60 * 60;

    private static final String RESERVE_SCRIPT = """
            if redis.call('exists', KEYS[1]) == 1 then return 0 end
            redis.call('hset', KEYS[1], 'groupId', ARGV[1], 'roomId', ARGV[2],
              'callType', ARGV[3], 'initiatorUserId', ARGV[4], 'sfuEndpoint', '',
              'startedAt', ARGV[5], 'updatedAt', ARGV[5], 'state', 'CREATING')
            redis.call('hset', KEYS[2], ARGV[4], ARGV[5])
            redis.call('expire', KEYS[1], ARGV[6])
            redis.call('expire', KEYS[2], ARGV[6])
            return 1
            """;
    private static final String ACTIVATE_SCRIPT = """
            if redis.call('hget', KEYS[1], 'roomId') ~= ARGV[1] then return 0 end
            redis.call('hset', KEYS[1], 'sfuEndpoint', ARGV[2], 'updatedAt', ARGV[3], 'state', 'ACTIVE')
            redis.call('expire', KEYS[1], ARGV[4])
            redis.call('expire', KEYS[2], ARGV[4])
            return 1
            """;
    private static final String ADMIT_SCRIPT = """
            if redis.call('hget', KEYS[1], 'state') ~= 'ACTIVE' then return 0 end
            if redis.call('hexists', KEYS[2], ARGV[1]) == 1 then
              redis.call('expire', KEYS[1], ARGV[4]); redis.call('expire', KEYS[2], ARGV[4]); return 1
            end
            if tonumber(ARGV[2]) > 0 and redis.call('hlen', KEYS[2]) >= tonumber(ARGV[2]) then return 2 end
            redis.call('hset', KEYS[2], ARGV[1], ARGV[3])
            redis.call('hset', KEYS[1], 'updatedAt', ARGV[3])
            redis.call('expire', KEYS[1], ARGV[4]); redis.call('expire', KEYS[2], ARGV[4])
            return 1
            """;
    private static final String REMOVE_SCRIPT = """
            if redis.call('exists', KEYS[1]) == 0 then return 0 end
            redis.call('hdel', KEYS[2], ARGV[1])
            if redis.call('hlen', KEYS[2]) == 0 then redis.call('del', KEYS[1], KEYS[2]); return 2 end
            redis.call('hset', KEYS[1], 'updatedAt', ARGV[2]); return 1
            """;
    private static final String END_SCRIPT = """
            if redis.call('exists', KEYS[1]) == 0 then return 0 end
            redis.call('del', KEYS[1], KEYS[2]); return 1
            """;

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
        try (CloseableRedisCommands redis = redisConfig.createSyncCommands()) {
            return read(redis.sync(), groupId);
        }
    }

    @Override
    public GroupCallReservation reserve(GroupCallSession session) {
        try (CloseableRedisCommands redis = redisConfig.createSyncCommands()) {
            RedisCommands<String, String> sync = redis.sync();
            Long created = sync.eval(RESERVE_SCRIPT, ScriptOutputType.INTEGER,
                    new String[]{groupKey(session.groupId()), memberKey(session.groupId())},
                    session.groupId(), session.roomId(), session.callType(), session.initiatorUserId(),
                    String.valueOf(session.startedAt()), String.valueOf(ttlSeconds));
            return new GroupCallReservation(read(sync, session.groupId()), Long.valueOf(1L).equals(created));
        }
    }

    @Override
    public GroupCallSession activate(String groupId, String roomId, String sfuEndpoint, long now) {
        try (CloseableRedisCommands redis = redisConfig.createSyncCommands()) {
            RedisCommands<String, String> sync = redis.sync();
            Long activated = sync.eval(ACTIVATE_SCRIPT, ScriptOutputType.INTEGER,
                    new String[]{groupKey(groupId), memberKey(groupId)}, roomId, sfuEndpoint,
                    String.valueOf(now), String.valueOf(ttlSeconds));
            return Long.valueOf(1L).equals(activated) ? read(sync, groupId) : null;
        }
    }

    @Override
    public GroupCallAdmission admit(String groupId, String userId, int maxParticipants, long now) {
        try (CloseableRedisCommands redis = redisConfig.createSyncCommands()) {
            RedisCommands<String, String> sync = redis.sync();
            Long result = sync.eval(ADMIT_SCRIPT, ScriptOutputType.INTEGER,
                    new String[]{groupKey(groupId), memberKey(groupId)}, userId,
                    String.valueOf(maxParticipants), String.valueOf(now), String.valueOf(ttlSeconds));
            long code = result != null ? result : 0L;
            return new GroupCallAdmission(read(sync, groupId), code == 1L, code == 2L);
        }
    }

    @Override
    public GroupCallSession removeParticipant(String groupId, String userId) {
        try (CloseableRedisCommands redis = redisConfig.createSyncCommands()) {
            RedisCommands<String, String> sync = redis.sync();
            GroupCallSession before = read(sync, groupId);
            Long result = sync.eval(REMOVE_SCRIPT, ScriptOutputType.INTEGER,
                    new String[]{groupKey(groupId), memberKey(groupId)}, userId,
                    String.valueOf(System.currentTimeMillis()));
            if (!Long.valueOf(2L).equals(result)) return read(sync, groupId);
            return before != null ? before.markEnded() : null;
        }
    }

    @Override
    public GroupCallSession end(String groupId) {
        try (CloseableRedisCommands redis = redisConfig.createSyncCommands()) {
            RedisCommands<String, String> sync = redis.sync();
            GroupCallSession before = read(sync, groupId);
            Long ended = sync.eval(END_SCRIPT, ScriptOutputType.INTEGER,
                    new String[]{groupKey(groupId), memberKey(groupId)});
            return Long.valueOf(1L).equals(ended) && before != null ? before.markEnded() : null;
        }
    }

    private GroupCallSession read(RedisCommands<String, String> sync, String groupId) {
        Map<String, String> data = sync.hgetall(groupKey(groupId));
        if (data == null || data.isEmpty()) return null;
        List<GroupCallParticipant> participants = sync.hgetall(memberKey(groupId)).entrySet().stream()
                .map(entry -> new GroupCallParticipant(entry.getKey(), parseLong(entry.getValue())))
                .sorted(Comparator.comparingLong(GroupCallParticipant::joinedAt))
                .toList();
        return new GroupCallSession(data.get("groupId"), data.get("roomId"), data.get("callType"),
                data.get("initiatorUserId"), data.get("sfuEndpoint"), parseLong(data.get("startedAt")),
                parseLong(data.get("updatedAt")), participants.size(), participants, false);
    }

    private static long parseLong(String value) {
        try { return value != null ? Long.parseLong(value) : 0L; }
        catch (NumberFormatException ignored) { return 0L; }
    }

    private static String groupKey(String groupId) { return GROUP_KEY_PREFIX + groupId; }
    private static String memberKey(String groupId) { return MEMBER_KEY_PREFIX + groupId; }
}
