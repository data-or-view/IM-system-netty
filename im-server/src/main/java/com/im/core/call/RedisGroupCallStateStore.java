package com.im.core.call;

import com.im.core.redis.CloseableRedisCommands;
import com.im.core.redis.RedisConfiguration;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.cluster.api.sync.RedisClusterCommands;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;

/** Redis-backed group-call state. Every returned transition snapshot is produced inside its Lua script. */
public class RedisGroupCallStateStore implements GroupCallStateStore {

    private static final String LEGACY_GROUP_KEY_PREFIX = "im:group_call:group:";
    private static final String LEGACY_MEMBER_KEY_PREFIX = "im:group_call:members:v2:";
    private static final String INTERMEDIATE_GROUP_KEY_PREFIX = "im:group_call:{state}:group:";
    private static final String INTERMEDIATE_MEMBER_KEY_PREFIX = "im:group_call:{state}:members:";
    private static final String TAGGED_GROUP_KEY_PREFIX = "im:group_call:v3:group:";
    private static final String TAGGED_MEMBER_KEY_PREFIX = "im:group_call:v3:members:";
    private static final String LAYOUT_MARKER_KEY = "im:group_call:key-layout";
    private static final String LEGACY_LAYOUT = "legacy";
    private static final String DRAINING_LAYOUT = "draining";
    private static final String TAGGED_LAYOUT = "tagged-v3";
    private static final long DEFAULT_TTL_SECONDS = 12 * 60 * 60;
    private static final long DEFAULT_CREATING_STALE_SECONDS = 30;

    private static final String LAYOUT_CAS_SCRIPT = """
            local current = redis.call('get', KEYS[1])
            if ARGV[1] == '' then
              if current then return 0 end
            elseif current ~= ARGV[1] then
              return 0
            end
            redis.call('set', KEYS[1], ARGV[2])
            return 1
            """;
    private static final String LEGACY_MULTI_LAYOUT_GUARD = """
            if redis.call('get', KEYS[3]) ~= 'legacy' then return {'LAYOUT_BLOCKED'} end
            """;
    private static final String LEGACY_INTEGER_LAYOUT_GUARD = """
            if redis.call('get', KEYS[3]) ~= 'legacy' then return -1 end
            """;

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
                redis.call('hget', KEYS[1], 'creationEpoch') or '0',
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
                'creationEpoch', '1',
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
              redis.call('hincrby', KEYS[1], 'creationEpoch', 1)
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

    private static final String VALIDATE_CREATION_OWNER_SCRIPT = SCRIPT_HELPERS + """
            if redis.call('exists', KEYS[1]) == 0 then return 0 end
            if redis.call('hget', KEYS[1], 'roomId') ~= ARGV[1] then return 0 end
            if lifecycle() ~= 'CREATING' then return 0 end
            local epoch = tonumber(redis.call('hget', KEYS[1], 'creationEpoch') or '0')
            if epoch ~= tonumber(ARGV[2]) then return 0 end
            redis.call('hset', KEYS[1], 'updatedAt', ARGV[3])
            redis.call('expire', KEYS[1], ARGV[4])
            redis.call('expire', KEYS[2], ARGV[4])
            return 1
            """;

    private static final String ACTIVATE_SCRIPT = SCRIPT_HELPERS + """
            if redis.call('exists', KEYS[1]) == 0 then return {'STALE'} end
            if redis.call('hget', KEYS[1], 'roomId') ~= ARGV[1] then return {'STALE'} end
            local epoch = tonumber(redis.call('hget', KEYS[1], 'creationEpoch') or '0')
            if epoch ~= tonumber(ARGV[2]) then return {'STALE'} end
            local state = lifecycle()
            if state == 'ACTIVE' then return snapshot('ACTIVE', state, false) end
            if state ~= 'CREATING' then return {'STALE'} end
            redis.call('hset', KEYS[1],
              'sfuEndpoint', ARGV[3],
              'updatedAt', ARGV[4],
              'state', 'ACTIVE')
            redis.call('expire', KEYS[1], ARGV[5])
            redis.call('expire', KEYS[2], ARGV[5])
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
    private final KeyLayout keyLayout;

    public RedisGroupCallStateStore(RedisConfiguration redisConfig) {
        this(redisConfig, LEGACY_LAYOUT);
    }

    public RedisGroupCallStateStore(RedisConfiguration redisConfig, String keyLayout) {
        this(redisConfig, DEFAULT_TTL_SECONDS, DEFAULT_CREATING_STALE_SECONDS, keyLayout);
    }

    RedisGroupCallStateStore(RedisConfiguration redisConfig, long ttlSeconds) {
        this(redisConfig, ttlSeconds, DEFAULT_CREATING_STALE_SECONDS, LEGACY_LAYOUT);
    }

    RedisGroupCallStateStore(RedisConfiguration redisConfig, long ttlSeconds, long creatingStaleSeconds) {
        this(redisConfig, ttlSeconds, creatingStaleSeconds, LEGACY_LAYOUT);
    }

    RedisGroupCallStateStore(RedisConfiguration redisConfig, long ttlSeconds,
                             long creatingStaleSeconds, String keyLayout) {
        this.redisConfig = redisConfig;
        this.ttlSeconds = ttlSeconds;
        this.creatingStaleMillis = Math.max(creatingStaleSeconds, 0) * 1000;
        this.keyLayout = KeyLayout.parse(keyLayout);
        if (this.keyLayout == KeyLayout.LEGACY && redisConfig.isClusterMode()) {
            throw new IllegalStateException("legacy group-call Redis keys cannot provide atomic transitions "
                    + "in Redis Cluster; drain legacy calls and set im.call.group.redis-key-layout=tagged-v3");
        }
    }

    @Override
    public GroupCallSession getActiveByGroup(String groupId) {
        try (CloseableRedisCommands redis = redisConfig.createSyncCommands()) {
            RedisClusterCommands<String, String> commands = redis.sync();
            ensureLayoutReady(commands, groupId);
            ScriptResult result = eval(commands, GET_ACTIVE_SCRIPT, groupId);
            return "ACTIVE".equals(result.code()) ? result.session() : null;
        }
    }

    @Override
    public GroupCallReservation reserve(String groupId, String roomId, String callType,
                                        String initiatorUserId, long now) {
        try (CloseableRedisCommands redis = redisConfig.createSyncCommands()) {
            RedisClusterCommands<String, String> commands = redis.sync();
            ensureLayoutReady(commands, groupId);
            ScriptResult result = eval(commands, RESERVE_SCRIPT, groupId,
                    groupId, roomId, callType, initiatorUserId, String.valueOf(now),
                    String.valueOf(ttlSeconds), String.valueOf(creatingStaleMillis));
            boolean created = "CREATED".equals(result.code()) || "RECOVERED".equals(result.code());
            return new GroupCallReservation(result.session(), created, "ACTIVE".equals(result.state()),
                    result.creationEpoch());
        }
    }

    @Override
    public boolean validateCreationOwner(String groupId, String roomId, long creationEpoch, long now) {
        try (CloseableRedisCommands redis = redisConfig.createSyncCommands()) {
            RedisClusterCommands<String, String> commands = redis.sync();
            ensureLayoutReady(commands, groupId);
            String script = keyLayout == KeyLayout.LEGACY
                    ? LEGACY_INTEGER_LAYOUT_GUARD + VALIDATE_CREATION_OWNER_SCRIPT
                    : VALIDATE_CREATION_OWNER_SCRIPT;
            Long result = commands.eval(script, ScriptOutputType.INTEGER,
                    scriptKeys(groupId), roomId,
                    String.valueOf(creationEpoch), String.valueOf(now), String.valueOf(ttlSeconds));
            if (Long.valueOf(-1L).equals(result)) throw layoutChanged();
            return Long.valueOf(1L).equals(result);
        }
    }

    @Override
    public GroupCallSession activate(String groupId, String roomId, long creationEpoch,
                                     String sfuEndpoint, long now) {
        try (CloseableRedisCommands redis = redisConfig.createSyncCommands()) {
            RedisClusterCommands<String, String> commands = redis.sync();
            ensureLayoutReady(commands, groupId);
            ScriptResult result = eval(commands, ACTIVATE_SCRIPT, groupId,
                    roomId, String.valueOf(creationEpoch), sfuEndpoint,
                    String.valueOf(now), String.valueOf(ttlSeconds));
            return "ACTIVATED".equals(result.code()) || "ACTIVE".equals(result.code())
                    ? result.session()
                    : null;
        }
    }

    @Override
    public GroupCallAdmission admit(String groupId, String userId, int maxParticipants, long now) {
        try (CloseableRedisCommands redis = redisConfig.createSyncCommands()) {
            RedisClusterCommands<String, String> commands = redis.sync();
            ensureLayoutReady(commands, groupId);
            ScriptResult result = eval(commands, ADMIT_SCRIPT, groupId,
                    userId, String.valueOf(maxParticipants), String.valueOf(now), String.valueOf(ttlSeconds));
            return new GroupCallAdmission(result.session(), "ADMITTED".equals(result.code()),
                    "FULL".equals(result.code()));
        }
    }

    @Override
    public GroupCallSession removeParticipant(String groupId, String userId,
                                              String expectedRoomId, long now) {
        try (CloseableRedisCommands redis = redisConfig.createSyncCommands()) {
            RedisClusterCommands<String, String> commands = redis.sync();
            ensureLayoutReady(commands, groupId);
            ScriptResult result = eval(commands, REMOVE_SCRIPT, groupId,
                    userId, expectedRoomId, String.valueOf(now), String.valueOf(ttlSeconds));
            return "LEFT".equals(result.code()) || "ENDED".equals(result.code())
                    ? result.session()
                    : null;
        }
    }

    @Override
    public GroupCallSession end(String groupId, String expectedRoomId, long now) {
        try (CloseableRedisCommands redis = redisConfig.createSyncCommands()) {
            RedisClusterCommands<String, String> commands = redis.sync();
            ensureLayoutReady(commands, groupId);
            ScriptResult result = eval(commands, END_SCRIPT, groupId,
                    expectedRoomId, String.valueOf(now));
            return "ENDED".equals(result.code()) ? result.session() : null;
        }
    }

    @SuppressWarnings("unchecked")
    private ScriptResult eval(RedisClusterCommands<String, String> commands,
                              String script,
                              String groupId,
                              String... arguments) {
        String guardedScript = keyLayout == KeyLayout.LEGACY
                ? LEGACY_MULTI_LAYOUT_GUARD + script
                : script;
        List<Object> values = commands.eval(guardedScript, ScriptOutputType.MULTI,
                scriptKeys(groupId), arguments);
        if (values == null || values.isEmpty()) return new ScriptResult("", "", null, 0L);

        String code = stringValue(values.get(0));
        if ("LAYOUT_BLOCKED".equals(code)) throw layoutChanged();
        if (values.size() < 11) return new ScriptResult(code, "", null, 0L);
        String state = stringValue(values.get(1));
        List<GroupCallParticipant> participants = new ArrayList<>();
        for (int index = 11; index + 1 < values.size(); index += 2) {
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
                "1".equals(stringValue(values.get(10))));
        return new ScriptResult(code, state, session, parseLong(stringValue(values.get(9))));
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

    private String[] scriptKeys(String groupId) {
        if (keyLayout == KeyLayout.LEGACY) {
            return new String[]{legacyGroupKey(groupId), legacyMemberKey(groupId), LAYOUT_MARKER_KEY};
        }
        return new String[]{groupKey(groupId), memberKey(groupId)};
    }

    private void ensureLayoutReady(RedisClusterCommands<String, String> commands, String groupId) {
        if (keyLayout == KeyLayout.LEGACY) {
            Boolean initialized = commands.setnx(LAYOUT_MARKER_KEY, LEGACY_LAYOUT);
            String current = Boolean.TRUE.equals(initialized) ? LEGACY_LAYOUT : commands.get(LAYOUT_MARKER_KEY);
            if (!LEGACY_LAYOUT.equals(current)) throw layoutChanged();
            return;
        }
        ensureTaggedLayoutReady(commands);
        if (legacyStateExistsForGroup(commands, groupId)) {
            throw new IllegalStateException("legacy group-call state exists for group " + groupId
                    + "; tagged-v3 operation refused");
        }
    }

    private void ensureTaggedLayoutReady(RedisClusterCommands<String, String> commands) {
        for (int attempt = 0; attempt < 4; attempt++) {
            String current = commands.get(LAYOUT_MARKER_KEY);
            if (TAGGED_LAYOUT.equals(current)) return;
            if (current != null && !LEGACY_LAYOUT.equals(current) && !DRAINING_LAYOUT.equals(current)) {
                throw new IllegalStateException("unsupported group-call Redis key layout marker: " + current);
            }
            if (!DRAINING_LAYOUT.equals(current)) {
                if (hasAnyLegacyState(commands)) {
                    throw new IllegalStateException("legacy group-call state must be drained before tagged-v3 cutover");
                }
                if (!compareAndSetLayout(commands, current, DRAINING_LAYOUT)) continue;
            }
            if (hasAnyLegacyState(commands)) {
                throw new IllegalStateException("legacy group-call state appeared during tagged-v3 cutover; "
                        + "compatibility writes are now blocked");
            }
            if (compareAndSetLayout(commands, DRAINING_LAYOUT, TAGGED_LAYOUT)
                    || TAGGED_LAYOUT.equals(commands.get(LAYOUT_MARKER_KEY))) {
                return;
            }
        }
        throw new IllegalStateException("group-call Redis key layout cutover did not converge");
    }

    private static boolean compareAndSetLayout(RedisClusterCommands<String, String> commands,
                                               String expected, String replacement) {
        Long changed = commands.eval(LAYOUT_CAS_SCRIPT, ScriptOutputType.INTEGER,
                new String[]{LAYOUT_MARKER_KEY}, expected != null ? expected : "", replacement);
        return Long.valueOf(1L).equals(changed);
    }

    private static boolean hasAnyLegacyState(RedisClusterCommands<String, String> commands) {
        return !commands.keys(LEGACY_GROUP_KEY_PREFIX + "*").isEmpty()
                || !commands.keys(LEGACY_MEMBER_KEY_PREFIX + "*").isEmpty()
                || !commands.keys(INTERMEDIATE_GROUP_KEY_PREFIX + "*").isEmpty()
                || !commands.keys(INTERMEDIATE_MEMBER_KEY_PREFIX + "*").isEmpty();
    }

    private static boolean legacyStateExistsForGroup(RedisClusterCommands<String, String> commands,
                                                     String groupId) {
        return exists(commands, legacyGroupKey(groupId))
                || exists(commands, legacyMemberKey(groupId))
                || exists(commands, LEGACY_GROUP_KEY_PREFIX + oldHashTag(groupId))
                || exists(commands, LEGACY_MEMBER_KEY_PREFIX + oldHashTag(groupId))
                || exists(commands, INTERMEDIATE_GROUP_KEY_PREFIX + groupId)
                || exists(commands, INTERMEDIATE_MEMBER_KEY_PREFIX + groupId);
    }

    private static boolean exists(RedisClusterCommands<String, String> commands, String key) {
        return Long.valueOf(1L).equals(commands.exists(key));
    }

    private static IllegalStateException layoutChanged() {
        return new IllegalStateException("group-call Redis key layout changed; legacy operation refused");
    }

    private static String legacyGroupKey(String groupId) {
        return LEGACY_GROUP_KEY_PREFIX + groupId;
    }

    private static String legacyMemberKey(String groupId) {
        return LEGACY_MEMBER_KEY_PREFIX + groupId;
    }

    private static String groupKey(String groupId) {
        return TAGGED_GROUP_KEY_PREFIX + hashTag(groupId);
    }

    private static String memberKey(String groupId) {
        return TAGGED_MEMBER_KEY_PREFIX + hashTag(groupId);
    }

    private static String hashTag(String groupId) {
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(groupId.getBytes(StandardCharsets.UTF_8));
        return "{g-" + encoded + "}";
    }

    private static String oldHashTag(String groupId) {
        return "{" + groupId.replace("{", "%7B").replace("}", "%7D") + "}";
    }

    private enum KeyLayout {
        LEGACY,
        TAGGED_V3;

        private static KeyLayout parse(String value) {
            if (value == null || value.isBlank() || LEGACY_LAYOUT.equalsIgnoreCase(value)) return LEGACY;
            if (TAGGED_LAYOUT.equalsIgnoreCase(value)) return TAGGED_V3;
            throw new IllegalArgumentException("unsupported group-call Redis key layout: " + value);
        }
    }

    private record ScriptResult(String code, String state, GroupCallSession session,
                                long creationEpoch) {
    }
}
