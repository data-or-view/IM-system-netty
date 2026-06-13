package com.wzg.idempotency;

import com.wzg.idempotency.config.IdempotencyConfig;
import com.wzg.idempotency.persistence.InMemoryPersistenceStore;
import com.wzg.idempotency.persistence.JdbcPersistenceStore;
import com.wzg.idempotency.persistence.RedissonPersistenceStore;
import org.redisson.api.RedissonClient;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IdempotencyFunctionalApiTest {

    @Test
    void returnsCachedResultForRepeatedKeyWithoutFrameworkAnnotations() {
        
        Idempotency.config()
                .withPersistenceStore(new InMemoryPersistenceStore())
                .withConfig(IdempotencyConfig.builder()
                        .withExpiration(Duration.ofMinutes(5))
                        .build())
                .configure();

        AtomicInteger calls = new AtomicInteger();

        Map<String, String> key = Map.of("orderId", "order-1001");

        String first = Idempotency.makeIdempotent(
                "createOrder",
                key,
                () -> "created-" + calls.incrementAndGet(),
                String.class);

        String second = Idempotency.makeIdempotent(
                "createOrder",
                key,
                () -> "created-" + calls.incrementAndGet(),
                String.class);

        assertEquals("created-1", first);
        assertEquals("created-1", second);
        assertEquals(1, calls.get());
    }

    @Test
    void configBuilderDoesNotExposeExpressionBasedKeySelection() {
        boolean hasJmesPathMethod = Arrays.stream(IdempotencyConfig.Builder.class.getMethods())
                .map(Method::getName)
                .anyMatch(name -> name.contains("JMESPath"));
        assertTrue(!hasJmesPathMethod);
    }

    @Test
    void jdbcStoreIsConstructedFromApplicationProvidedDataSource() throws Exception {
        assertNotNull(JdbcPersistenceStore.class.getConstructor(DataSource.class));
        assertNotNull(JdbcPersistenceStore.class.getConstructor(DataSource.class, String.class));
    }

    @Test
    void redissonStoreIsConstructedFromApplicationProvidedClient() throws Exception {
        assertNotNull(RedissonPersistenceStore.class.getConstructor(RedissonClient.class));
        assertNotNull(RedissonPersistenceStore.class.getConstructor(
                RedissonClient.class,
                String.class,
                long.class,
                long.class));
    }
}
