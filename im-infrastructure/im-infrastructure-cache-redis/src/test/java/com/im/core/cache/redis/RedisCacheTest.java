package com.im.core.cache.redis;

import com.im.core.cache.CacheStats;
import com.im.core.serialization.Serializer;
import com.im.core.serialization.jackson.JacksonSerializer;
import io.lettuce.core.RedisClient;
import org.junit.jupiter.api.*;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RedisCache 集成测试。
 *
 * <p>需要本地 Redis 实例，连接信息：
 * <pre>redis://difyai123456@localhost:6379/1</pre>
 * 使用 DB 1 避免影响默认数据库。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RedisCacheTest {

    private static final String KEY_PREFIX = "test:redis-cache:";
    private static final Duration DEFAULT_TTL = Duration.ofSeconds(60);

    private RedisCache<String, String> cache;
    private RedisClient client;

    @BeforeAll
    void setup() {
        client = RedisClient.create("redis://difyai123456@localhost:6379/1");
        Serializer<String, String> serializer = new JacksonSerializer<String>();
        cache = new RedisCache<>(
                client,
                Function.identity(),
                serializer,
                String.class,
                KEY_PREFIX,
                DEFAULT_TTL
        );
        cache.start();
    }

    @AfterEach
    void tearDown() {
        // 每次测试后清理
        cache.clear();
    }

    @AfterAll
    void cleanup() {
        cache.close();
        client.shutdown();
    }

    // ========== get / put ==========

    @Test
    void shouldReturnEmptyWhenKeyDoesNotExist() {
        Optional<String> result = cache.get("nonexistent-" + System.nanoTime());
        assertTrue(result.isEmpty());
    }

    @Test
    void shouldPutAndGet() {
        cache.put("put-get", "hello");
        assertEquals(Optional.of("hello"), cache.get("put-get"));
    }

    @Test
    void shouldOverwriteExistingKey() {
        cache.put("overwrite", "v1");
        cache.put("overwrite", "v2");
        assertEquals(Optional.of("v2"), cache.get("overwrite"));
    }

    @Test
    void shouldHandleEmptyStringValue() {
        cache.put("empty", "");
        assertEquals(Optional.of(""), cache.get("empty"));
    }

    // ========== getAllPresent ==========

    @Test
    void shouldGetAllPresent() {
        cache.put("gap-a", "1");
        cache.put("gap-b", "2");

        Map<String, Optional<String>> result = cache.getAllPresent(Set.of("gap-a", "gap-b", "gap-nonexistent"));

        assertEquals(Optional.of("1"), result.get("gap-a"));
        assertEquals(Optional.of("2"), result.get("gap-b"));
        assertTrue(result.get("gap-nonexistent").isEmpty());
    }

    @Test
    void shouldReturnEmptyMapForEmptyKeys() {
        Map<String, Optional<String>> result = cache.getAllPresent(Set.of());
        assertTrue(result.isEmpty());
    }

    // ========== putIfAbsent ==========

    @Test
    void shouldPutIfAbsent() {
        assertTrue(cache.putIfAbsent("pia-key", "first"));
        assertEquals(Optional.of("first"), cache.get("pia-key"));
    }

    @Test
    void shouldNotOverwriteExistingKeyWithPutIfAbsent() {
        cache.put("pia-existing", "original");
        assertFalse(cache.putIfAbsent("pia-existing", "new-value"));
        assertEquals(Optional.of("original"), cache.get("pia-existing"));
    }

    // ========== invalidate ==========

    @Test
    void shouldInvalidateExistingKey() {
        cache.put("inv-key", "value");
        assertTrue(cache.invalidate("inv-key"));
        assertTrue(cache.get("inv-key").isEmpty());
    }

    @Test
    void shouldReturnFalseWhenInvalidateNonexistentKey() {
        assertFalse(cache.invalidate("inv-nonexistent-" + System.nanoTime()));
    }

    // ========== invalidateAll ==========

    @Test
    void shouldInvalidateMultipleKeys() {
        cache.put("inv-all-a", "1");
        cache.put("inv-all-b", "2");
        cache.put("inv-all-c", "3");

        cache.invalidateAll(Set.of("inv-all-a", "inv-all-b"));

        assertTrue(cache.get("inv-all-a").isEmpty());
        assertTrue(cache.get("inv-all-b").isEmpty());
        assertEquals(Optional.of("3"), cache.get("inv-all-c"));
    }

    @Test
    void shouldNotThrowWhenInvalidateAllEmpty() {
        assertDoesNotThrow(() -> cache.invalidateAll(Set.of()));
    }

    // ========== clear ==========

    @Test
    void shouldClearAllEntries() {
        cache.put("clr-a", "1");
        cache.put("clr-b", "2");
        cache.clear();

        assertTrue(cache.get("clr-a").isEmpty());
        assertTrue(cache.get("clr-b").isEmpty());
    }

    @Test
    void shouldReturnEmptyAfterClear() {
        cache.put("clr-only", "value");
        cache.clear();
        assertEquals(0, cache.estimatedSize());
    }

    // ========== estimatedSize ==========

    @Test
    void estimatedSizeShouldBeNonNegative() {
        assertTrue(cache.estimatedSize() >= 0);
    }

    @Test
    void estimatedSizeShouldIncreaseAfterPut() {
        long before = cache.estimatedSize();
        cache.put("size-test", "v");
        long after = cache.estimatedSize();
        assertTrue(after >= before);
    }

    // ========== stats ==========

    @Test
    void statsShouldRecordHits() {
        cache.put("stat-hit", "val");
        cache.get("stat-hit");
        cache.get("stat-hit");
        CacheStats stats = cache.stats();
        assertTrue(stats.hitCount() >= 2);
    }

    @Test
    void statsShouldRecordMisses() {
        cache.get("stat-miss-" + System.nanoTime());
        CacheStats stats = cache.stats();
        assertTrue(stats.missCount() >= 1);
    }

    @Test
    void statsShouldNotBeNull() {
        assertNotNull(cache.stats());
    }

    // ========== TTL ==========

    @Test
    void shouldExpireAfterTtl() throws Exception {
        // 使用极短 TTL 的 cache 实例
        Serializer<String, String> ser = new JacksonSerializer<>();
        var shortTtlCache = new RedisCache<>(
                client,
                Function.identity(),
                ser,
                String.class,
                KEY_PREFIX + "ttl-test:",
                Duration.ofSeconds(1)
        );
        shortTtlCache.start();
        try (shortTtlCache) {
            shortTtlCache.put("expire-key", "value");
            assertEquals(Optional.of("value"), shortTtlCache.get("expire-key"));

            // 等待过期
            Thread.sleep(1100);
            assertTrue(shortTtlCache.get("expire-key").isEmpty());
        }
    }

    // ========== key prefix isolation ==========

    @Test
    void shouldIsolateKeysByPrefix() {
        Serializer<String, String> ser = new JacksonSerializer<>();
        var cacheA = new RedisCache<>(
                client, Function.identity(), ser, String.class,
                "prefix-a:", Duration.ofSeconds(60)
        );
        cacheA.start();
        var cacheB = new RedisCache<>(
                client, Function.identity(), ser, String.class,
                "prefix-b:", Duration.ofSeconds(60)
        );
        cacheB.start();

        try (cacheA; cacheB) {
            cacheA.put("shared-key", "from-a");
            cacheB.put("shared-key", "from-b");

            assertEquals(Optional.of("from-a"), cacheA.get("shared-key"));
            assertEquals(Optional.of("from-b"), cacheB.get("shared-key"));
        }
    }
}
