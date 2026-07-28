package com.im.core.call;

import com.im.core.redis.CloseableRedisCommands;
import com.im.core.redis.RedisConfiguration;
import io.lettuce.core.cluster.SlotHash;
import io.lettuce.core.cluster.api.sync.RedisClusterCommands;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RedisGroupCallStateStoreE2ETest {

    private static final String GROUP_KEY_PREFIX = "im:group_call:group:";
    private static final String MEMBER_KEY_PREFIX = "im:group_call:members:v2:";

    @Test
    void reservationsAdmissionsAndEndAreAtomicAcrossRedisConnections() throws Exception {
        RedisConfiguration redis = redisOrSkip();
        String groupId = "group-call-e2e-" + UUID.randomUUID();
        try {
            List<GroupCallReservation> reservations = concurrently(8, index -> {
                GroupCallStateStore store = taggedStore(redis);
                return reserve(store, session(groupId, "room-" + index));
            });

            assertEquals(1, reservations.stream().map(reservation -> reservation.session().roomId()).distinct().count());
            assertEquals(1, reservations.stream().filter(GroupCallReservation::created).count());
            String roomId = reservations.getFirst().session().roomId();

            GroupCallStateStore store = taggedStore(redis);
            GroupCallAdmission creatingAdmission = store.admit(groupId, "u1", 3, System.currentTimeMillis());
            assertFalse(creatingAdmission.joined());
            assertFalse(creatingAdmission.full());
            assertNotNull(creatingAdmission.session());

            long creationEpoch = reservations.stream()
                    .filter(GroupCallReservation::created)
                    .findFirst()
                    .orElseThrow()
                    .creationEpoch();
            assertNotNull(store.activate(groupId, roomId, creationEpoch,
                    "ws://livekit.test", System.currentTimeMillis()));
            assertTrue(store.admit(groupId, "u1", 3, System.currentTimeMillis()).joined());

            List<GroupCallAdmission> admissions = concurrently(8, index ->
                    taggedStore(redis).admit(groupId, "u" + (index + 2), 3,
                            System.currentTimeMillis()));

            GroupCallSession active = store.getActiveByGroup(groupId);
            assertNotNull(active);
            assertEquals(3, active.participantCount());
            assertTrue(admissions.stream().anyMatch(GroupCallAdmission::full));

            GroupCallAdmission retry = store.admit(groupId, "u1", 3, System.currentTimeMillis());
            assertTrue(retry.joined());
            assertFalse(retry.full());
            assertEquals(3, retry.session().participantCount());

            assertTrue(store.end(groupId, roomId, System.currentTimeMillis()).ended());
            GroupCallAdmission afterEnd = store.admit(groupId, "u99", 3, System.currentTimeMillis());
            assertFalse(afterEnd.joined());
            assertFalse(afterEnd.full());
            assertNull(afterEnd.session());
        } finally {
            cleanup(redis, groupId);
        }
    }

    @Test
    void creatingReservationIsHiddenUntilActivation() {
        RedisConfiguration redis = redisOrSkip();
        String groupId = "group-call-creating-" + UUID.randomUUID();
        try {
            GroupCallStateStore store = taggedStore(redis);
            GroupCallReservation reservation = reserve(store, session(groupId, "room-creating"));

            assertTrue(reservation.created());
            assertNull(store.getActiveByGroup(groupId));

            GroupCallSession active = store.activate(groupId, reservation.session().roomId(),
                    reservation.creationEpoch(), "ws://livekit.test", System.currentTimeMillis());
            assertNotNull(active);
            assertEquals("room-creating", store.getActiveByGroup(groupId).roomId());
        } finally {
            cleanup(redis, groupId);
        }
    }

    @Test
    void staleCreatingReservationIsRecoveredOnceWithoutChangingRoomId() throws Exception {
        RedisConfiguration redis = redisOrSkip();
        String groupId = "group-call-stale-" + UUID.randomUUID();
        try {
            GroupCallStateStore store = taggedStore(redis);
            GroupCallSession stale = new GroupCallSession(groupId, "room-original", "video", "owner", "",
                    1L, 1L, 1, List.of(new GroupCallParticipant("owner", 1L)), false);
            assertTrue(reserve(store, stale).created());

            List<GroupCallReservation> recoveries = concurrently(8, index -> {
                long now = TimeUnit.MINUTES.toMillis(10) + index;
                GroupCallSession retry = new GroupCallSession(groupId, "room-retry-" + index, "video", "owner", "",
                        now, now, 1, List.of(new GroupCallParticipant("owner", now)), false);
                return reserve(taggedStore(redis), retry);
            });

            assertEquals(1, recoveries.stream().filter(GroupCallReservation::created).count());
            assertTrue(recoveries.stream().allMatch(result -> "room-original".equals(result.session().roomId())));
        } finally {
            cleanup(redis, groupId);
        }
    }

    @Test
    void recoveredReservationFencesTheOriginalCreationEpoch() {
        RedisConfiguration redis = redisOrSkip();
        String groupId = "group-call-epoch-" + UUID.randomUUID();
        try {
            GroupCallStateStore store = new RedisGroupCallStateStore(redis, 3600, 0, "tagged-v3");
            GroupCallReservation original = store.reserve(
                    groupId, "room-preserved", "video", "owner", 1L);
            GroupCallReservation recovered = store.reserve(
                    groupId, "room-retry", "video", "owner", 2L);

            assertEquals("room-preserved", recovered.session().roomId());
            assertTrue(recovered.creationEpoch() > original.creationEpoch());
            assertFalse(store.validateCreationOwner(groupId, original.session().roomId(),
                    original.creationEpoch(), 3L));
            assertNull(store.activate(groupId, original.session().roomId(), original.creationEpoch(),
                    "ws://stale.test", 4L));
            assertTrue(store.validateCreationOwner(groupId, recovered.session().roomId(),
                    recovered.creationEpoch(), 5L));
            assertNotNull(store.activate(groupId, recovered.session().roomId(), recovered.creationEpoch(),
                    "ws://livekit.test", 6L));
        } finally {
            cleanup(redis, groupId);
        }
    }

    @Test
    void legacyCompatibilityAndExplicitTaggedCutoverNeverCreateDisjointState() throws Exception {
        RedisConfiguration redis = redisOrSkip();
        if (redis.isClusterMode()) {
            redis.close();
            Assumptions.abort("legacy compatibility phase requires standalone Redis");
        }
        String groupId = "group-call-layout-" + UUID.randomUUID();
        String replacementGroupId = groupId + "-replacement";
        try {
            seedLegacyActiveCall(redis, groupId, "room-legacy");
            RedisGroupCallStateStore legacy = storeForLayout(redis, "legacy");
            RedisGroupCallStateStore tagged = storeForLayout(redis, "tagged-v3");

            assertEquals("room-legacy", legacy.getActiveByGroup(groupId).roomId());
            assertThrows(IllegalStateException.class, () -> tagged.getActiveByGroup(groupId));
            assertEquals(0L, exists(redis, taggedGroupKey(groupId)));

            assertTrue(legacy.end(groupId, "room-legacy", 2L).ended());
            GroupCallReservation cutover = tagged.reserve(
                    replacementGroupId, "room-tagged", "video", "owner", 3L);
            assertTrue(cutover.created());
            assertThrows(IllegalStateException.class, () -> legacy.reserve(
                    groupId, "room-after-cutover", "video", "owner", 4L));
        } finally {
            cleanupLayoutTest(redis, groupId, replacementGroupId);
        }
    }

    @Test
    void taggedCutoverRefusesLegacyStateAcrossRedisMasters() throws Exception {
        RedisConfiguration redis = redisOrSkip();
        String groupId = "group-call-legacy-guard-" + UUID.randomUUID();
        try {
            seedLegacyActiveCall(redis, groupId, "room-legacy");
            RedisGroupCallStateStore tagged = taggedStore(redis);

            assertThrows(IllegalStateException.class, () -> tagged.reserve(
                    groupId, "room-tagged", "video", "owner", 2L));
            assertEquals(0L, exists(redis, taggedGroupKey(groupId)));
        } finally {
            cleanup(redis, groupId);
        }
    }

    @Test
    void legacyLayoutFailsClosedInRedisCluster() {
        RedisConfiguration redis = redisOrSkip();
        if (!redis.isClusterMode()) {
            redis.close();
            Assumptions.abort("Redis Cluster is required for the legacy-layout fail-closed check");
        }
        try {
            assertThrows(IllegalStateException.class,
                    () -> new RedisGroupCallStateStore(redis, "legacy"));
        } finally {
            redis.close();
        }
    }

    @Test
    void taggedKeysUseInjectiveEncodingForBracesAndLiteralPercents() throws Exception {
        Method groupKey = RedisGroupCallStateStore.class.getDeclaredMethod("groupKey", String.class);
        groupKey.setAccessible(true);

        String braceKey = (String) groupKey.invoke(null, "a{b");
        String percentKey = (String) groupKey.invoke(null, "a%7Bb");

        assertNotEquals(braceKey, percentKey);
        assertEquals(SlotHash.getSlot(braceKey), SlotHash.getSlot(taggedMemberKey("a{b")));
        assertEquals(SlotHash.getSlot(percentKey), SlotHash.getSlot(taggedMemberKey("a%7Bb")));
    }

    @Test
    void rejectsMalformedUtf16GroupIdsBeforeRedisKeyEncoding() {
        RedisConfiguration redis = redisOrSkip();
        String malformedHighSurrogate = "group-\uD800";
        String malformedLowSurrogate = "group-\uDC00";
        try {
            GroupCallStateStore store = taggedStore(redis);

            assertThrows(IllegalArgumentException.class, () -> store.reserve(
                    malformedHighSurrogate, "room-high", "video", "owner", 1L));
            assertThrows(IllegalArgumentException.class, () -> store.reserve(
                    malformedLowSurrogate, "room-low", "video", "owner", 1L));
        } finally {
            cleanupLayoutTest(redis, malformedHighSurrogate, malformedLowSurrogate);
        }
    }

    @Test
    void usesInjectivePerGroupHashTaggedKeysAfterCutover() {
        RedisConfiguration redis = redisOrSkip();
        String groupId = "group-call-keys-" + UUID.randomUUID();
        try {
            GroupCallStateStore store = taggedStore(redis);
            reserve(store, session(groupId, "room-key-layout"));

            try (CloseableRedisCommands commands = redis.createSyncCommands()) {
                RedisClusterCommands<String, String> sync = commands.sync();
                assertEquals(1L, sync.exists(groupKey(groupId)));
                assertEquals(1L, sync.exists(memberKey(groupId)));
                assertEquals(0L, sync.exists(GROUP_KEY_PREFIX + groupId));
                assertEquals(0L, sync.exists(MEMBER_KEY_PREFIX + groupId));
                assertEquals(0L, sync.exists("im:group_call:{state}:group:" + groupId));
                assertEquals(SlotHash.getSlot(groupKey(groupId)), SlotHash.getSlot(memberKey(groupId)));
                assertNotEquals(SlotHash.getSlot(groupKey(groupId)),
                        SlotHash.getSlot(groupKey(groupId + "-other")));
            }
        } finally {
            cleanup(redis, groupId);
        }
    }

    @Test
    void staleEndAndLeaveCannotMutateAReplacementRoom() {
        RedisConfiguration redis = redisOrSkip();
        String groupId = "group-call-aba-" + UUID.randomUUID();
        try {
            GroupCallStateStore store = taggedStore(redis);
            GroupCallReservation old = store.reserve(groupId, "room-old", "video", "owner", 1L);
            assertNotNull(store.activate(groupId, old.session().roomId(), old.creationEpoch(),
                    "ws://livekit.test", 2L));
            assertTrue(store.end(groupId, old.session().roomId(), 3L).ended());

            GroupCallReservation replacement = store.reserve(groupId, "room-new", "video", "owner", 4L);
            assertNotNull(store.activate(groupId, replacement.session().roomId(), replacement.creationEpoch(),
                    "ws://livekit.test", 5L));

            assertNull(store.end(groupId, old.session().roomId(), 6L));
            assertNull(store.removeParticipant(groupId, "owner", old.session().roomId(), 7L));
            GroupCallSession active = store.getActiveByGroup(groupId);
            assertNotNull(active);
            assertEquals("room-new", active.roomId());
            assertEquals(1, active.participantCount());
        } finally {
            cleanup(redis, groupId);
        }
    }

    @Test
    void activationAndEndOverlapNeverResurrectsTheEndedRoom() throws Exception {
        RedisConfiguration redis = redisOrSkip();
        String groupId = "group-call-activation-end-" + UUID.randomUUID();
        try {
            GroupCallStateStore store = taggedStore(redis);
            for (int iteration = 0; iteration < 20; iteration++) {
                String roomId = "room-race-" + iteration;
                long baseTime = iteration * 10L;
                GroupCallReservation reservation = store.reserve(
                        groupId, roomId, "video", "owner", baseTime + 1L);
                assertTrue(reservation.created());

                List<GroupCallSession> results = concurrently(2, index -> index == 0
                        ? taggedStore(redis).activate(
                                groupId, roomId, reservation.creationEpoch(),
                                "ws://livekit.test", baseTime + 2L)
                        : taggedStore(redis).end(
                                groupId, roomId, baseTime + 3L));

                assertTrue(results.stream()
                        .filter(java.util.Objects::nonNull)
                        .allMatch(result -> roomId.equals(result.roomId())));
                assertNull(store.getActiveByGroup(groupId));
            }
        } finally {
            cleanup(redis, groupId);
        }
    }

    private static GroupCallSession session(String groupId, String roomId) {
        long now = System.currentTimeMillis();
        return new GroupCallSession(groupId, roomId, "video", "owner", "", now, now, 1,
                List.of(new GroupCallParticipant("owner", now)), false);
    }

    private static <T> List<T> concurrently(int count, IndexedCallable<T> callable) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(count);
        CountDownLatch ready = new CountDownLatch(count);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                int taskIndex = index;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    return callable.call(taskIndex);
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            List<T> results = new ArrayList<>(count);
            for (Future<T> future : futures) {
                results.add(future.get(10, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    private static RedisConfiguration redisOrSkip() {
        try {
            RedisConfiguration.Builder builder = RedisConfiguration.builder()
                    .username(env("IM_E2E_REDIS_USERNAME", env("IM_REDIS_USERNAME", "")))
                    .password(env("IM_E2E_REDIS_PASSWORD", env("IM_REDIS_PASSWORD", "difyai123456")))
                    .timeout(Duration.ofSeconds(2));
            String clusterNodes = env("IM_E2E_REDIS_CLUSTER_NODES", "").trim();
            if (clusterNodes.isEmpty()) {
                builder.host(env("IM_E2E_REDIS_HOST", env("IM_REDIS_HOST", "127.0.0.1")))
                        .port(Integer.parseInt(env("IM_E2E_REDIS_PORT", env("IM_REDIS_PORT", "6379"))))
                        .database(Integer.parseInt(env("IM_E2E_REDIS_DATABASE", env("IM_REDIS_DATABASE", "0"))));
            } else {
                builder.clusterNodes(Arrays.stream(clusterNodes.split(","))
                        .map(String::trim)
                        .filter(node -> !node.isEmpty())
                        .toList());
            }
            RedisConfiguration redis = builder.build();
            try (CloseableRedisCommands commands = redis.createSyncCommands()) {
                commands.<RedisClusterCommands<String, String>>sync().ping();
            }
            return redis;
        } catch (RuntimeException e) {
            Assumptions.abort("Redis group-call integration test skipped because Redis is unreachable: " + e.getMessage());
            throw e;
        }
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value != null ? value : fallback;
    }

    private static GroupCallReservation reserve(GroupCallStateStore store, GroupCallSession session) {
        return store.reserve(session.groupId(), session.roomId(), session.callType(),
                session.initiatorUserId(), session.startedAt());
    }

    private static String groupKey(String groupId) {
        return "im:group_call:v3:group:" + encodedHashTag(groupId);
    }

    private static String memberKey(String groupId) {
        return "im:group_call:v3:members:" + encodedHashTag(groupId);
    }

    private static String encodedHashTag(String groupId) {
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(groupId.getBytes(StandardCharsets.UTF_8));
        return "{g-" + encoded + "}";
    }

    private static RedisGroupCallStateStore storeForLayout(RedisConfiguration redis,
                                                            String layout) {
        return new RedisGroupCallStateStore(redis, layout);
    }

    private static RedisGroupCallStateStore taggedStore(RedisConfiguration redis) {
        return new RedisGroupCallStateStore(redis, "tagged-v3");
    }

    private static String taggedGroupKey(String groupId) throws Exception {
        Method method = RedisGroupCallStateStore.class.getDeclaredMethod("groupKey", String.class);
        method.setAccessible(true);
        return (String) method.invoke(null, groupId);
    }

    private static String taggedMemberKey(String groupId) throws Exception {
        Method method = RedisGroupCallStateStore.class.getDeclaredMethod("memberKey", String.class);
        method.setAccessible(true);
        return (String) method.invoke(null, groupId);
    }

    private static void seedLegacyActiveCall(RedisConfiguration redis, String groupId, String roomId) {
        try (CloseableRedisCommands commands = redis.createSyncCommands()) {
            RedisClusterCommands<String, String> sync = commands.sync();
            sync.hset(GROUP_KEY_PREFIX + groupId, Map.of(
                    "groupId", groupId,
                    "roomId", roomId,
                    "callType", "video",
                    "initiatorUserId", "owner",
                    "sfuEndpoint", "ws://legacy.test",
                    "startedAt", "1",
                    "updatedAt", "1"));
            sync.hset(MEMBER_KEY_PREFIX + groupId, "owner", "1");
        }
    }

    private static long exists(RedisConfiguration redis, String key) {
        try (CloseableRedisCommands commands = redis.createSyncCommands()) {
            return commands.<RedisClusterCommands<String, String>>sync().exists(key);
        }
    }

    private static void cleanupLayoutTest(RedisConfiguration redis, String... groupIds) {
        try (CloseableRedisCommands commands = redis.createSyncCommands()) {
            RedisClusterCommands<String, String> sync = commands.sync();
            for (String groupId : groupIds) {
                sync.del(GROUP_KEY_PREFIX + groupId);
                sync.del(MEMBER_KEY_PREFIX + groupId);
                sync.del(groupKey(groupId), memberKey(groupId));
            }
            sync.del("im:group_call:key-layout");
        } finally {
            redis.close();
        }
    }

    private static void cleanup(RedisConfiguration redis, String groupId) {
        try (CloseableRedisCommands commands = redis.createSyncCommands()) {
            RedisClusterCommands<String, String> sync = commands.sync();
            sync.del(groupKey(groupId), memberKey(groupId));
            sync.del(GROUP_KEY_PREFIX + groupId);
            sync.del(MEMBER_KEY_PREFIX + groupId);
            sync.del("im:group_call:{state}:group:" + groupId,
                    "im:group_call:{state}:members:" + groupId);
            sync.del("im:group_call:key-layout");
        } finally {
            redis.close();
        }
    }

    @FunctionalInterface
    private interface IndexedCallable<T> {
        T call(int index) throws Exception;
    }
}
