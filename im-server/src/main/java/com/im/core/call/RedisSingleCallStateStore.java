package com.im.core.call;

import com.im.api.SignalingAction;
import com.im.core.redis.CloseableRedisCommands;
import com.im.core.redis.RedisConfiguration;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.cluster.api.sync.RedisClusterCommands;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class RedisSingleCallStateStore implements SingleCallStateStore {

    private static final String ROOM_KEY_PREFIX = "im:single_call:{state}:room:";
    private static final String USER_KEY_PREFIX = "im:single_call:{state}:user:";
    private static final String DEADLINE_KEY = "im:single_call:{state}:deadlines";
    private static final String PENDING_SIGNAL_KEY_PREFIX = "im:single_call:{state}:pending_signal:";
    private static final long DEFAULT_TTL_SECONDS = 2 * 60 * 60;
    private static final long MIN_PENDING_SIGNAL_TTL_SECONDS = 24 * 60 * 60;
    private static final long TIMEOUT_DELIVERY_LEASE_MILLIS = 10_000L;
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
            local status = redis.call('hget', KEYS[1], 'status')
            if status ~= ARGV[3] and status ~= ARGV[4] then
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
            local status = redis.call('hget', KEYS[1], 'status')
            if status ~= ARGV[2] and status ~= ARGV[3] then
              return 0
            end
            redis.call('zrem', KEYS[4], ARGV[1])
            redis.call('del', KEYS[1], KEYS[2], KEYS[3])
            return 1
            """;
    private static final String TERMINAL_SIGNAL_TRANSITION_SCRIPT = """
            local pendingRoom = redis.call('hget', KEYS[5], 'roomId')
            if pendingRoom ~= false then
              if pendingRoom == ARGV[1]
                  and redis.call('hget', KEYS[5], 'actorId') == ARGV[2]
                  and redis.call('hget', KEYS[5], 'peerUserId') == ARGV[3]
                  and redis.call('hget', KEYS[5], 'action') == ARGV[4]
                  and redis.call('hget', KEYS[5], 'clientMsgId') == ARGV[5]
                  and redis.call('hget', KEYS[5], 'messageJson') == ARGV[6] then
                redis.call('expire', KEYS[5], ARGV[11])
                return 2
              end
              return 0
            end

            local caller = redis.call('hget', KEYS[1], 'callerId')
            local callee = redis.call('hget', KEYS[1], 'calleeId')
            if caller == false or callee == false then
              return 0
            end
            if ARGV[3] ~= caller and ARGV[3] ~= callee then
              return 0
            end

            local status = redis.call('hget', KEYS[1], 'status')
            if ARGV[4] == 'ACCEPT' then
              if ARGV[2] ~= callee or status ~= ARGV[7] then
                return 0
              end
            elseif ARGV[4] == 'REJECT' then
              if ARGV[2] ~= callee or (status ~= ARGV[7] and status ~= ARGV[8]) then
                return 0
              end
            elseif ARGV[4] == 'CANCEL' then
              if ARGV[2] ~= caller or (status ~= ARGV[7] and status ~= ARGV[8]) then
                return 0
              end
            elseif ARGV[4] == 'HANGUP' then
              if (ARGV[2] ~= caller and ARGV[2] ~= callee)
                  or (status ~= ARGV[7] and status ~= ARGV[8]) then
                return 0
              end
            else
              return 0
            end

            redis.call('hset', KEYS[5],
              'roomId', ARGV[1],
              'actorId', ARGV[2],
              'peerUserId', ARGV[3],
              'action', ARGV[4],
              'clientMsgId', ARGV[5],
              'messageJson', ARGV[6])
            redis.call('expire', KEYS[5], ARGV[11])
            redis.call('zrem', KEYS[4], ARGV[1])

            if ARGV[4] == 'ACCEPT' then
              redis.call('hset', KEYS[1], 'status', ARGV[8], 'acceptedAt', ARGV[9])
              redis.call('expire', KEYS[1], ARGV[10])
              redis.call('expire', KEYS[2], ARGV[10])
              redis.call('expire', KEYS[3], ARGV[10])
            else
              redis.call('del', KEYS[1], KEYS[2], KEYS[3])
            end
            return 1
            """;
    private static final String ACKNOWLEDGE_TERMINAL_SIGNAL_SCRIPT = """
            if redis.call('hget', KEYS[1], 'roomId') ~= ARGV[1]
                or redis.call('hget', KEYS[1], 'actorId') ~= ARGV[2]
                or redis.call('hget', KEYS[1], 'peerUserId') ~= ARGV[3]
                or redis.call('hget', KEYS[1], 'action') ~= ARGV[4]
                or redis.call('hget', KEYS[1], 'clientMsgId') ~= ARGV[5] then
              return 0
            end
            redis.call('del', KEYS[1])
            return 1
            """;
    private static final String CLAIM_EXPIRED_RINGING_SCRIPT = """
            local claimed = {}
            for candidate = 1, (#KEYS - 1) / 3 do
              local keyIndex = 2 + (candidate - 1) * 3
              local argIndex = 6 + (candidate - 1) * 3
              local roomKey = KEYS[keyIndex]
              local callerKey = KEYS[keyIndex + 1]
              local calleeKey = KEYS[keyIndex + 2]
              local roomId = ARGV[argIndex]
              local expectedCaller = ARGV[argIndex + 1]
              local expectedCallee = ARGV[argIndex + 2]
              local caller = redis.call('hget', roomKey, 'callerId')
              local callee = redis.call('hget', roomKey, 'calleeId')
              local dueAt = redis.call('zscore', KEYS[1], roomId)
              local status = redis.call('hget', roomKey, 'status')
              local deadlineAt = redis.call('hget', roomKey, 'deadlineAt')
              local claimable = dueAt ~= false and tonumber(dueAt) <= tonumber(ARGV[4])
                  and caller == expectedCaller and callee == expectedCallee
              local timedOut = status == ARGV[2]
              if claimable and ((status == ARGV[1] and deadlineAt ~= false
                    and tonumber(deadlineAt) <= tonumber(ARGV[4])) or timedOut) then
                local callType = redis.call('hget', roomKey, 'callType') or ''
                local sfuEndpoint = redis.call('hget', roomKey, 'sfuEndpoint') or ''
                local startedAt = redis.call('hget', roomKey, 'startedAt') or '0'
                local acceptedAt = redis.call('hget', roomKey, 'acceptedAt') or '0'
                deadlineAt = deadlineAt or '0'
                local caller = redis.call('hget', roomKey, 'callerId')
                local callee = redis.call('hget', roomKey, 'calleeId')
                if not timedOut then
                  redis.call('del', callerKey, calleeKey)
                  redis.call('hset', roomKey, 'status', ARGV[2])
                end
                redis.call('expire', roomKey, ARGV[3])
                redis.call('zadd', KEYS[1], tonumber(ARGV[4]) + tonumber(ARGV[5]), roomId)
                table.insert(claimed, roomId)
                table.insert(claimed, caller)
                table.insert(claimed, callee)
                table.insert(claimed, callType)
                table.insert(claimed, ARGV[2])
                table.insert(claimed, sfuEndpoint)
                table.insert(claimed, startedAt)
                table.insert(claimed, acceptedAt)
                table.insert(claimed, deadlineAt)
              elseif dueAt ~= false and status ~= ARGV[1] and not timedOut then
                redis.call('zrem', KEYS[1], roomId)
              end
            end
            return claimed
            """;

    private final RedisConfiguration redisConfig;
    private final long ttlSeconds;
    private final long pendingSignalTtlSeconds;

    public RedisSingleCallStateStore(RedisConfiguration redisConfig) {
        this(redisConfig, DEFAULT_TTL_SECONDS, MIN_PENDING_SIGNAL_TTL_SECONDS);
    }

    public RedisSingleCallStateStore(RedisConfiguration redisConfig, Duration pendingSignalRetention) {
        this(redisConfig, DEFAULT_TTL_SECONDS,
                pendingSignalRetention != null ? pendingSignalRetention.toSeconds() : MIN_PENDING_SIGNAL_TTL_SECONDS);
    }

    RedisSingleCallStateStore(RedisConfiguration redisConfig, long ttlSeconds) {
        this(redisConfig, ttlSeconds, MIN_PENDING_SIGNAL_TTL_SECONDS);
    }

    RedisSingleCallStateStore(RedisConfiguration redisConfig, long ttlSeconds, long pendingSignalTtlSeconds) {
        this.redisConfig = redisConfig;
        this.ttlSeconds = ttlSeconds;
        this.pendingSignalTtlSeconds = Math.max(MIN_PENDING_SIGNAL_TTL_SECONDS, pendingSignalTtlSeconds);
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
    public TerminalSignalIntent getPendingTerminalSignal(String roomId) {
        if (!hasText(roomId)) return null;
        try (CloseableRedisCommands redis = redisConfig.createSyncCommands()) {
            RedisClusterCommands<String, String> sync = redis.sync();
            Map<String, String> data = sync.hgetall(pendingSignalKey(roomId));
            if (data == null || data.isEmpty()) return null;
            try {
                return new TerminalSignalIntent(
                        data.get("roomId"),
                        data.get("actorId"),
                        data.get("peerUserId"),
                        SignalingAction.valueOf(data.get("action")),
                        data.get("clientMsgId"),
                        data.get("messageJson"));
            } catch (RuntimeException e) {
                throw new IllegalStateException("invalid pending terminal signal for room " + roomId, e);
            }
        }
    }

    @Override
    public boolean transitionTerminalSignal(TerminalSignalIntent intent) {
        if (intent == null || !hasText(intent.roomId()) || !hasText(intent.actorId())
                || !hasText(intent.peerUserId()) || intent.action() == null || !hasText(intent.clientMsgId())
                || !hasText(intent.messageJson())) {
            return false;
        }
        try (CloseableRedisCommands redis = redisConfig.createSyncCommands()) {
            RedisClusterCommands<String, String> sync = redis.sync();
            SingleCallSession session = read(sync, intent.roomId());
            String callerId = session != null ? session.callerId() : "missing-caller:" + intent.roomId();
            String calleeId = session != null ? session.calleeId() : "missing-callee:" + intent.roomId();
            String[] keys = new String[]{
                    roomKey(intent.roomId()),
                    userKey(callerId),
                    userKey(calleeId),
                    deadlineKey(),
                    pendingSignalKey(intent.roomId())};
            Long result = sync.eval(TERMINAL_SIGNAL_TRANSITION_SCRIPT, ScriptOutputType.INTEGER, keys,
                    intent.roomId(),
                    intent.actorId(),
                    intent.peerUserId(),
                    intent.action().name(),
                    intent.clientMsgId(),
                    intent.messageJson(),
                    SingleCallSession.STATUS_RINGING,
                    SingleCallSession.STATUS_ACCEPTED,
                    String.valueOf(System.currentTimeMillis()),
                    String.valueOf(ttlSeconds),
                    String.valueOf(pendingSignalTtlSeconds));
            return Long.valueOf(1L).equals(result) || Long.valueOf(2L).equals(result);
        }
    }

    @Override
    public boolean acknowledgeTerminalSignal(TerminalSignalIntent intent) {
        if (intent == null || !hasText(intent.roomId())) return false;
        try (CloseableRedisCommands redis = redisConfig.createSyncCommands()) {
            RedisClusterCommands<String, String> sync = redis.sync();
            Long result = sync.eval(ACKNOWLEDGE_TERMINAL_SIGNAL_SCRIPT, ScriptOutputType.INTEGER,
                    new String[]{pendingSignalKey(intent.roomId())},
                    intent.roomId(),
                    intent.actorId(),
                    intent.peerUserId(),
                    intent.action().name(),
                    intent.clientMsgId());
            return Long.valueOf(1L).equals(result);
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
            List<String> roomIds = sync.zrangebyscore(deadlineKey(), "-inf", String.valueOf(nowEpochMillis), 0, limit);
            if (roomIds == null || roomIds.isEmpty()) return List.of();

            List<ClaimCandidate> candidates = new ArrayList<>(roomIds.size());
            for (String roomId : roomIds) {
                Map<String, String> data = sync.hgetall(roomKey(roomId));
                String callerId = data != null ? data.get("callerId") : null;
                String calleeId = data != null ? data.get("calleeId") : null;
                candidates.add(new ClaimCandidate(roomId, callerId, calleeId));
            }

            String[] keys = new String[1 + candidates.size() * 3];
            keys[0] = deadlineKey();
            String[] args = new String[5 + candidates.size() * 3];
            args[0] = SingleCallSession.STATUS_RINGING;
            args[1] = SingleCallSession.STATUS_TIMED_OUT;
            args[2] = String.valueOf(ttlSeconds);
            args[3] = String.valueOf(nowEpochMillis);
            args[4] = String.valueOf(TIMEOUT_DELIVERY_LEASE_MILLIS);
            for (int index = 0; index < candidates.size(); index++) {
                ClaimCandidate candidate = candidates.get(index);
                int keyIndex = 1 + index * 3;
                int argIndex = 5 + index * 3;
                keys[keyIndex] = roomKey(candidate.roomId());
                keys[keyIndex + 1] = userKey(candidate.callerIdOrMissing());
                keys[keyIndex + 2] = userKey(candidate.calleeIdOrMissing());
                args[argIndex] = candidate.roomId();
                args[argIndex + 1] = candidate.callerIdOrMissing();
                args[argIndex + 2] = candidate.calleeIdOrMissing();
            }

            @SuppressWarnings("unchecked")
            List<Object> rawSnapshots = sync.eval(CLAIM_EXPIRED_RINGING_SCRIPT, ScriptOutputType.MULTI, keys, args);
            return toClaimedSessions(rawSnapshots);
        }
    }

    @Override
    public void acknowledgeTimeoutDelivery(String roomId) {
        if (!hasText(roomId)) return;
        try (CloseableRedisCommands redis = redisConfig.createSyncCommands()) {
            RedisClusterCommands<String, String> sync = redis.sync();
            sync.zrem(deadlineKey(), roomId);
        }
    }

    @Override
    public SingleCallSession end(String roomId) {
        if (!hasText(roomId)) return null;
        try (CloseableRedisCommands redis = redisConfig.createSyncCommands()) {
            RedisClusterCommands<String, String> sync = redis.sync();
            SingleCallSession session = read(sync, roomId);
            if (session == null) return null;
            Long ended = sync.eval(END_SCRIPT, ScriptOutputType.INTEGER, callKeys(session), session.roomId(),
                    SingleCallSession.STATUS_RINGING, SingleCallSession.STATUS_ACCEPTED);
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
                    callKeys(session), actorId, session.roomId(), SingleCallSession.STATUS_RINGING,
                    SingleCallSession.STATUS_ACCEPTED);
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

    private static String pendingSignalKey(String roomId) {
        return PENDING_SIGNAL_KEY_PREFIX + roomId;
    }

    private static String[] callKeys(SingleCallSession session) {
        return new String[]{roomKey(session.roomId()), userKey(session.callerId()), userKey(session.calleeId()), deadlineKey()};
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    private static List<SingleCallSession> toClaimedSessions(List<Object> rawSnapshots) {
        if (rawSnapshots == null || rawSnapshots.isEmpty()) return List.of();
        final int fieldsPerSession = 9;
        if (rawSnapshots.size() % fieldsPerSession != 0) {
            throw new IllegalStateException("invalid single-call timeout claim snapshot");
        }
        List<SingleCallSession> claimed = new ArrayList<>(rawSnapshots.size() / fieldsPerSession);
        for (int index = 0; index < rawSnapshots.size(); index += fieldsPerSession) {
            claimed.add(new SingleCallSession(
                    valueAt(rawSnapshots, index),
                    valueAt(rawSnapshots, index + 1),
                    valueAt(rawSnapshots, index + 2),
                    valueAt(rawSnapshots, index + 3),
                    valueAt(rawSnapshots, index + 4),
                    emptyToNull(valueAt(rawSnapshots, index + 5)),
                    parseLong(valueAt(rawSnapshots, index + 6)),
                    parseLong(valueAt(rawSnapshots, index + 7)),
                    parseLong(valueAt(rawSnapshots, index + 8))));
        }
        return claimed;
    }

    private static String valueAt(List<Object> values, int index) {
        Object value = values.get(index);
        return value == null ? "" : String.valueOf(value);
    }

    private record ClaimCandidate(String roomId, String callerId, String calleeId) {
        String callerIdOrMissing() {
            return callerId == null ? "missing:" + roomId : callerId;
        }

        String calleeIdOrMissing() {
            return calleeId == null ? "missing:" + roomId : calleeId;
        }
    }
}
