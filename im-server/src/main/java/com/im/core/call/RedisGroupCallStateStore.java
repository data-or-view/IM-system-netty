package com.im.core.call;

import com.im.core.redis.CloseableRedisCommands;
import com.im.core.redis.RedisConfiguration;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.cluster.api.sync.RedisClusterCommands;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Redis-backed group-call state. Every returned transition snapshot is produced inside its Lua script. */
public class RedisGroupCallStateStore implements GroupCallStateStore {

    private static final String GROUP_KEY_PREFIX = "im:group_call:group:";
    private static final String MEMBER_KEY_PREFIX = "im:group_call:members:v2:";
    private static final long DEFAULT_TTL_SECONDS = 12 * 60 * 60;
    private static final long DEFAULT_CREATING_STALE_SECONDS = 30;

    private static final String SCRIPT_HELPERS = """
            local function lifecycle()
              local state = redis.call('hget', KEYS[1], 'state')
              if not state or state == '' then
                local endpoint = redis.call('hget', KEYS[1], 'sfuEndpoint')
                state = endpoint and endpoint ~= '' and 'ACTIVE' or 'CREATING'
                redis.call('hset', KEYS[1], 'state', state)
              end
              return state
            end
            local function snapshot(code, state, ended)
              local result = {
                code,
                state or '',
                redis.call('hget', KEYS[1], 'groupId') or '',
                redis.call('hget', KEYS[1], 'roomId') or '',
                redis.call('hget', KEYS[1], 'callType') or '',
                redis.call('hget', KEYS[1], 'initiatorUserId') or '',
                redis.call('hget', KEYS[1], 'sfuEndpoint') or '',
                redis.call('hget', KEYS[1], 'startedAt') or '0',
                redis.call('hget', KEYS[1], 'updatedAt') or '0',
                ended and '1' or '0'
              }
              local members = redis.call('hgetall', KEYS[2])
              for index = 1, #members do
                result[#result + 1] = members[index]
              end
              return result
            end
            """;

    private static final String GET_ACTIVE_SCRIPT = SCRIPT_HELPERS + """
            if redis.call('exists', KEYS[1]) == 0 then return {'NOT_ACTIVE'} end
            local state = lifecycle()
            if state ~= 'ACTIVE' then return {'NOT_ACTIVE'} end
            return snapshot('ACTIVE', state, false)
            """;

    private static final String RESERVE_SCRIPT = SCRIPT_HELPERS + """
            if redis.call('exists', KEYS[1]) == 0 then
              redis.call('hset', KEYS[1],
                'groupId', ARGV[1],
                'roomId', ARGV[2],
                'callType', ARGV[3],
                'initiatorUserId', ARGV[4],
                'sfuEndpoint', '',
                'startedAt', ARGV[5],
                'updatedAt', ARGV[5],
                'state', 'CREATING')
              redis.call('hset', KEYS[2], ARGV[4], ARGV[5])
              redis.call('expire', KEYS[1], ARGV[6])
              redis.call('expire', KEYS[2], ARGV[6])
              return snapshot('CREATED', 'CREATING', false)
            end

            local state = lifecycle()
            if state == 'ACTIVE' then
              return snapshot('EXISTING', state, false)
            end

            local updatedAt = tonumber(redis.call('hget', KEYS[1], 'updatedAt') or '0')
            local now = tonumber(ARGV[5])
            local staleMillis = tonumber(ARGV[7])
            if now - updatedAt >= staleMillis then
              redis.call('hset', KEYS[1], 'updatedAt', ARGV[5], 'state', 'CREATING')
              local initiator = redis.call('hget', KEYS[1], 'initiatorUserId')
              local startedAt = redis.call('hget', KEYS[1], 'startedAt') or ARGV[5]
              if initiator and initiator ~= '' then
                redis.call('hsetnx', KEYS[2], initiator, startedAt)
              end
              redis.call('expire', KEYS[1], ARGV[6])
              redis.call('expire', KEYS[2], ARGV[6])
              return snapshot('RECOVERED', 'CREATING', false)
            end
            return snapshot('CREATING', 'CREATING', false)
            """;

    private static final String ACTIVATE_SCRIPT = SCRIPT_HELPERS + """
            if redis.call('exists', KEYS[1]) == 0 then return {'STALE'} end
            if redis.call('hget', KEYS[1], 'roomId') ~= ARGV[1] then return {'STALE'} end
            local state = lifecycle()
            if state == 'ACTIVE' then return snapshot('ACTIVE', state, false) end
            if state ~= 'CREATING' then return {'STALE'} end
            redis.call('hset', KEYS[1],
              'sfuEndpoint', ARGV[2],
              'updatedAt', ARGV[3],
              'state', 'ACTIVE')
            redis.call('expire', KEYS[1], ARGV[4])
            redis.call('expire', KEYS[2], ARGV[4])
            return snapshot('ACTIVATED', 'ACTIVE', false)
            """;

    private static final String ADMIT_SCRIPT = SCRIPT_HELPERS + """
            if redis.call('exists', KEYS[1]) == 0 then return {'NOT_ACTIVE'} end
            local state = lifecycle()
            if state ~= 'ACTIVE' then return snapshot('NOT_ACTIVE', state, false) end
            if redis.call('hexists', KEYS[2], ARGV[1]) == 1 then
              redis.call('expire', KEYS[1], ARGV[4])
              redis.call('expire', KEYS[2], ARGV[4])
              return snapshot('ADMITTED', state, false)
            end
            if tonumber(ARGV[2]) > 0 and redis.call('hlen', KEYS[2]) >= tonumber(ARGV[2]) then
              return snapshot('FULL', state, false)
            end
            redis.call('hset', KEYS[2], ARGV[1], ARGV[3])
            redis.call('hset', KEYS[1], 'updatedAt', ARGV[3])
            redis.call('expire', KEYS[1], ARGV[4])
            redis.call('expire', KEYS[2], ARGV[4])
            return snapshot('ADMITTED', state, false)
            """;

    private static final String REMOVE_SCRIPT = SCRIPT_HELPERS + """
            if redis.call('exists', KEYS[1]) == 0 then return {'MISSING'} end
            if redis.call('hget', KEYS[1], 'roomId') ~= ARGV[2] then return {'STALE'} end
            local state = lifecycle()
            if state ~= 'ACTIVE' then return {'NOT_ACTIVE'} end
            redis.call('hdel', KEYS[2], ARGV[1])
            redis.call('hset', KEYS[1], 'updatedAt', ARGV[3])
            if redis.call('hlen', KEYS[2]) == 0 then
              local result = snapshot('ENDED', state, true)
              redis.call('del', KEYS[1], KEYS[2])
              return result
            end
            redis.call('expire', KEYS[1], ARGV[4])
            redis.call('expire', KEYS[2], ARGV[4])
            return snapshot('LEFT', state, false)
            """;

    private static final String END_SCRIPT = SCRIPT_HELPERS + """
            if redis.call('exists', KEYS[1]) == 0 then return {'MISSING'} end
            if redis.call('hget', KEYS[1], 'roomId') ~= ARGV[1] then return {'STALE'} end
            local state = lifecycle()
            redis.call('hset', KEYS[1], 'updatedAt', ARGV[2])
            local result = snapshot('ENDED', state, true)
            redis.call('del', KEYS[1], KEYS[2])
            return result
            """;

    private final RedisConfiguration redisConfig;
    private final long ttlSeconds;
    private final long creatingStaleMillis;

    public RedisGroupCallStateStore(RedisConfiguration redisConfig) {
        this(redisConfig, DEFAULT_TTL_SECONDS, DEFAULT_CREATING_STALE_SECONDS);
    }

    RedisGroupCallStateStore(RedisConfiguration redisConfig, long ttlSeconds) {
        this(redisConfig, ttlSeconds, DEFAULT_CREATING_STALE_SECONDS);
    }

    RedisGroupCallStateStore(RedisConfiguration redisConfig, long ttlSeconds, long creatingStaleSeconds) {
        this.redisConfig = redisConfig;
        this.ttlSeconds = ttlSeconds;
        this.creatingStaleMillis = Math.max(creatingStaleSeconds, 0) * 1000;
    }

    @Override
    public GroupCallSession getActiveByGroup(String groupId) {
        try (CloseableRedisCommands redis = redisConfig.createSyncCommands()) {
            ScriptResult result = eval(redis.sync(), GET_ACTIVE_SCRIPT, groupId);
            return "ACTIVE".equals(result.code()) ? result.session() : null;
        }
    }

    @Override
    public GroupCallReservation reserve(String groupId, String roomId, String callType,
                                        String initiatorUserId, long now) {
        try (CloseableRedisCommands redis = redisConfig.createSyncCommands()) {
            ScriptResult result = eval(redis.sync(), RESERVE_SCRIPT, groupId,
                    groupId, roomId, callType, initiatorUserId, String.valueOf(now),
                    String.valueOf(ttlSeconds), String.valueOf(creatingStaleMillis));
            boolean created = "CREATED".equals(result.code()) || "RECOVERED".equals(result.code());
            return new GroupCallReservation(result.session(), created, "ACTIVE".equals(result.state()));
        }
    }

    @Override
    public GroupCallSession activate(String groupId, String roomId, String sfuEndpoint, long now) {
        try (CloseableRedisCommands redis = redisConfig.createSyncCommands()) {
            ScriptResult result = eval(redis.sync(), ACTIVATE_SCRIPT, groupId,
                    roomId, sfuEndpoint, String.valueOf(now), String.valueOf(ttlSeconds));
            return "ACTIVATED".equals(result.code()) || "ACTIVE".equals(result.code())
                    ? result.session()
                    : null;
        }
    }

    @Override
    public GroupCallAdmission admit(String groupId, String userId, int maxParticipants, long now) {
        try (CloseableRedisCommands redis = redisConfig.createSyncCommands()) {
            ScriptResult result = eval(redis.sync(), ADMIT_SCRIPT, groupId,
                    userId, String.valueOf(maxParticipants), String.valueOf(now), String.valueOf(ttlSeconds));
            return new GroupCallAdmission(result.session(), "ADMITTED".equals(result.code()),
                    "FULL".equals(result.code()));
        }
    }

    @Override
    public GroupCallSession removeParticipant(String groupId, String userId,
                                              String expectedRoomId, long now) {
        try (CloseableRedisCommands redis = redisConfig.createSyncCommands()) {
            ScriptResult result = eval(redis.sync(), REMOVE_SCRIPT, groupId,
                    userId, expectedRoomId, String.valueOf(now), String.valueOf(ttlSeconds));
            return "LEFT".equals(result.code()) || "ENDED".equals(result.code())
                    ? result.session()
                    : null;
        }
    }

    @Override
    public GroupCallSession end(String groupId, String expectedRoomId, long now) {
        try (CloseableRedisCommands redis = redisConfig.createSyncCommands()) {
            ScriptResult result = eval(redis.sync(), END_SCRIPT, groupId,
                    expectedRoomId, String.valueOf(now));
            return "ENDED".equals(result.code()) ? result.session() : null;
        }
    }

    @SuppressWarnings("unchecked")
    private ScriptResult eval(RedisClusterCommands<String, String> commands,
                              String script,
                              String groupId,
                              String... arguments) {
        List<Object> values = commands.eval(script, ScriptOutputType.MULTI,
                new String[]{groupKey(groupId), memberKey(groupId)}, arguments);
        if (values == null || values.isEmpty()) return new ScriptResult("", "", null);

        String code = stringValue(values.get(0));
        if (values.size() < 10) return new ScriptResult(code, "", null);
        String state = stringValue(values.get(1));
        List<GroupCallParticipant> participants = new ArrayList<>();
        for (int index = 10; index + 1 < values.size(); index += 2) {
            String userId = stringValue(values.get(index));
            if (!userId.isBlank()) {
                participants.add(new GroupCallParticipant(userId, parseLong(stringValue(values.get(index + 1)))));
            }
        }
        participants.sort(Comparator.comparingLong(GroupCallParticipant::joinedAt));
        GroupCallSession session = new GroupCallSession(
                stringValue(values.get(2)),
                stringValue(values.get(3)),
                stringValue(values.get(4)),
                stringValue(values.get(5)),
                stringValue(values.get(6)),
                parseLong(stringValue(values.get(7))),
                parseLong(stringValue(values.get(8))),
                participants.size(),
                participants,
                "1".equals(stringValue(values.get(9))));
        return new ScriptResult(code, state, session);
    }

    private static String stringValue(Object value) {
        return value != null ? value.toString() : "";
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private static String groupKey(String groupId) {
        return GROUP_KEY_PREFIX + hashTag(groupId);
    }

    private static String memberKey(String groupId) {
        return MEMBER_KEY_PREFIX + hashTag(groupId);
    }

    private static String hashTag(String groupId) {
        return "{" + groupId.replace("{", "%7B").replace("}", "%7D") + "}";
    }

    private record ScriptResult(String code, String state, GroupCallSession session) {
    }
}
