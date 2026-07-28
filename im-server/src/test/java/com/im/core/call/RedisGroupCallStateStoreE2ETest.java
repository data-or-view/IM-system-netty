package com.im.core.call;

import com.im.core.redis.CloseableRedisCommands;
import com.im.core.redis.RedisConfiguration;
import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedisGroupCallStateStoreE2ETest {

    private static final String GROUP_KEY_PREFIX = "im:group_call:{state}:group:";
    private static final String MEMBER_KEY_PREFIX = "im:group_call:{state}:members:";

    @Test
    void reservationsAdmissionsAndEndAreAtomicAcrossRedisConnections() throws Exception {
        RedisConfiguration redis = redisOrSkip();
        String groupId = "group-call-e2e-" + UUID.randomUUID();
        try {
            List<GroupCallReservation> reservations = concurrently(8, index -> {
                GroupCallStateStore store = new RedisGroupCallStateStore(redis);
                return store.reserve(session(groupId, "room-" + index));
            });

            assertEquals(1, reservations.stream().map(reservation -> reservation.session().roomId()).distinct().count());
            assertEquals(1, reservations.stream().filter(GroupCallReservation::created).count());
            String roomId = reservations.getFirst().session().roomId();

            GroupCallStateStore store = new RedisGroupCallStateStore(redis);
            GroupCallAdmission creatingAdmission = store.admit(groupId, "u1", 3, System.currentTimeMillis());
            assertFalse(creatingAdmission.joined());
            assertFalse(creatingAdmission.full());
            assertNotNull(creatingAdmission.session());

            assertNotNull(store.activate(groupId, roomId, "ws://livekit.test", System.currentTimeMillis()));
            assertTrue(store.admit(groupId, "u1", 3, System.currentTimeMillis()).joined());

            List<GroupCallAdmission> admissions = concurrently(8, index ->
                    new RedisGroupCallStateStore(redis).admit(groupId, "u" + (index + 2), 3,
                            System.currentTimeMillis()));

            GroupCallSession active = store.getActiveByGroup(groupId);
            assertNotNull(active);
            assertEquals(3, active.participantCount());
            assertTrue(admissions.stream().anyMatch(GroupCallAdmission::full));

            GroupCallAdmission retry = store.admit(groupId, "u1", 3, System.currentTimeMillis());
            assertTrue(retry.joined());
            assertFalse(retry.full());
            assertEquals(3, retry.session().participantCount());

            assertTrue(store.end(groupId).ended());
            GroupCallAdmission afterEnd = store.admit(groupId, "u99", 3, System.currentTimeMillis());
            assertFalse(afterEnd.joined());
            assertFalse(afterEnd.full());
            assertNull(afterEnd.session());
        } finally {
            try (CloseableRedisCommands commands = redis.createSyncCommands()) {
                commands.<RedisCommands<String, String>>sync().del(
                        GROUP_KEY_PREFIX + groupId, MEMBER_KEY_PREFIX + groupId);
            } finally {
                redis.close();
            }
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
            RedisConfiguration redis = RedisConfiguration.builder()
                    .host(env("IM_E2E_REDIS_HOST", env("IM_REDIS_HOST", "127.0.0.1")))
                    .port(Integer.parseInt(env("IM_E2E_REDIS_PORT", env("IM_REDIS_PORT", "6379"))))
                    .username(env("IM_E2E_REDIS_USERNAME", env("IM_REDIS_USERNAME", "")))
                    .password(env("IM_E2E_REDIS_PASSWORD", env("IM_REDIS_PASSWORD", "difyai123456")))
                    .database(Integer.parseInt(env("IM_E2E_REDIS_DATABASE", env("IM_REDIS_DATABASE", "0"))))
                    .timeout(Duration.ofSeconds(2))
                    .build();
            try (CloseableRedisCommands commands = redis.createSyncCommands()) {
                commands.<RedisCommands<String, String>>sync().ping();
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

    @FunctionalInterface
    private interface IndexedCallable<T> {
        T call(int index) throws Exception;
    }
}
