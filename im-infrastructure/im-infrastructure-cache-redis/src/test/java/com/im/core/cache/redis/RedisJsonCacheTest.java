package com.im.core.cache.redis;

import com.im.core.serialization.jackson.JacksonSerializer;
import io.lettuce.core.KeyValue;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.SetArgs;
import io.lettuce.core.cluster.api.async.RedisClusterAsyncCommands;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedisJsonCacheTest {

    @Test
    void storesAndReadsJsonThroughSharedRedisCommands() {
        FakeRedisCommands fake = new FakeRedisCommands();
        RedisJsonCache<String, TestValue> cache = cache(fake);

        cache.put("a", new TestValue("alice", 1));

        assertEquals(Optional.of(new TestValue("alice", 1)), cache.get("a"));
        assertTrue(fake.data.containsKey("test:a"));
    }

    @Test
    void getAllPresentPreservesMisses() {
        FakeRedisCommands fake = new FakeRedisCommands();
        RedisJsonCache<String, TestValue> cache = cache(fake);
        cache.put("a", new TestValue("alice", 1));

        Map<String, Optional<TestValue>> values = cache.getAllPresent(Set.of("a", "missing"));

        assertEquals(Optional.of(new TestValue("alice", 1)), values.get("a"));
        assertTrue(values.get("missing").isEmpty());
    }

    @Test
    void putIfAbsentUsesNxSemantics() {
        FakeRedisCommands fake = new FakeRedisCommands();
        RedisJsonCache<String, TestValue> cache = cache(fake);

        assertTrue(cache.putIfAbsent("a", new TestValue("first", 1)));
        assertFalse(cache.putIfAbsent("a", new TestValue("second", 2)));

        assertEquals(Optional.of(new TestValue("first", 1)), cache.get("a"));
    }

    @Test
    void invalidateRemovesExistingKeysAndClearIsUnsupported() {
        FakeRedisCommands fake = new FakeRedisCommands();
        RedisJsonCache<String, TestValue> cache = cache(fake);
        cache.put("a", new TestValue("alice", 1));

        assertTrue(cache.invalidate("a"));
        assertTrue(cache.get("a").isEmpty());
        assertThrows(UnsupportedOperationException.class, cache::clear);
    }

    private static RedisJsonCache<String, TestValue> cache(FakeRedisCommands fake) {
        return new RedisJsonCache<>(
                fake.proxy(),
                Function.identity(),
                new JacksonSerializer<>(),
                TestValue.class,
                "test:",
                Duration.ofSeconds(30));
    }

    private record TestValue(String name, int version) {
    }

    private static final class FakeRedisCommands {
        private final Map<String, String> data = new ConcurrentHashMap<>();

        @SuppressWarnings("unchecked")
        RedisClusterAsyncCommands<String, String> proxy() {
            return (RedisClusterAsyncCommands<String, String>) Proxy.newProxyInstance(
                    RedisClusterAsyncCommands.class.getClassLoader(),
                    new Class<?>[]{RedisClusterAsyncCommands.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "get" -> future(data.get((String) args[0]));
                        case "mget" -> future(mget((String[]) args[0]));
                        case "setex" -> {
                            data.put((String) args[0], (String) args[2]);
                            yield future("OK");
                        }
                        case "set" -> future(set((String) args[0], (String) args[1], (SetArgs) args[2]));
                        case "del" -> future(del((String[]) args[0]));
                        default -> throw new UnsupportedOperationException(method.toString());
                    });
        }

        private java.util.List<KeyValue<String, String>> mget(String[] keys) {
            return Arrays.stream(keys)
                    .map(key -> data.containsKey(key)
                            ? KeyValue.just(key, data.get(key))
                            : KeyValue.<String, String>empty(key))
                    .toList();
        }

        private String set(String key, String value, SetArgs args) {
            if (data.containsKey(key)) {
                return null;
            }
            data.put(key, value);
            return "OK";
        }

        private Long del(String[] keys) {
            long removed = 0;
            for (String key : keys) {
                if (data.remove(key) != null) {
                    removed++;
                }
            }
            return removed;
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> RedisFuture<T> future(T value) {
        CompletableFuture<T> delegate = CompletableFuture.completedFuture(value);
        InvocationHandler handler = (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return method.invoke(delegate, args);
            }
            return CompletableFuture.class
                    .getMethod(method.getName(), method.getParameterTypes())
                    .invoke(delegate, args);
        };
        return (RedisFuture<T>) Proxy.newProxyInstance(
                RedisFuture.class.getClassLoader(),
                new Class<?>[]{RedisFuture.class},
                handler);
    }
}
