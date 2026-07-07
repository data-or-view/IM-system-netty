package com.wzg.idempotency.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzg.idempotency.core.JsonConfig;
import com.wzg.idempotency.exception.IdempotencyItemAlreadyExistsException;
import com.wzg.idempotency.exception.IdempotencyItemNotFoundException;
import org.redisson.api.RBucket;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

/**
 * Redisson-based persistence store.
 *
 * <p>The application owns the Redisson dependency version and provides the configured
 * {@link RedissonClient} instance.</p>
 */
public class RedissonPersistenceStore extends BasePersistenceStore {
    private static final Logger LOG = LoggerFactory.getLogger(RedissonPersistenceStore.class);
    private static final String DEFAULT_KEY_PREFIX = "idempotency:";
    private static final String LOCK_KEY_SUFFIX = ":lock";

    private final RedissonClient redissonClient;
    private final ObjectMapper objectMapper;
    private final String keyPrefix;
    private final long lockWaitTime;
    private final long lockLeaseTime;

    public RedissonPersistenceStore(RedissonClient redissonClient) {
        this(redissonClient, DEFAULT_KEY_PREFIX, 10, 30);
    }

    public RedissonPersistenceStore(RedissonClient redissonClient, String keyPrefix,
                                    long lockWaitTime, long lockLeaseTime) {
        this.redissonClient = redissonClient;
        this.keyPrefix = keyPrefix;
        this.lockWaitTime = lockWaitTime;
        this.lockLeaseTime = lockLeaseTime;
        this.objectMapper = JsonConfig.get().getObjectMapper();
    }

    @Override
    public DataRecord getRecord(String idempotencyKey) throws IdempotencyItemNotFoundException {
        String key = storageKey(idempotencyKey);
        RBucket<String> bucket = redissonClient.getBucket(key);
        String data = bucket.get();
        if (data == null) {
            throw new IdempotencyItemNotFoundException(idempotencyKey);
        }
        return deserialize(data);
    }

    @Override
    public void putRecord(DataRecord record, Instant now) throws IdempotencyItemAlreadyExistsException {
        String key = storageKey(record.getIdempotencyKey());
        RLock lock = redissonClient.getLock(lockKey(key));

        try {
            boolean acquired = lock.tryLock(lockWaitTime, lockLeaseTime, TimeUnit.SECONDS);
            if (!acquired) {
                throw new IdempotencyItemAlreadyExistsException(
                        "Failed to acquire lock for idempotency key", null, null);
            }

            try {
                RBucket<String> bucket = redissonClient.getBucket(key);
                String existingData = bucket.get();
                if (existingData != null) {
                    DataRecord existing = deserialize(existingData);
                    if (!existing.isExpired(now)) {
                        throw new IdempotencyItemAlreadyExistsException(
                                "Record already exists", null, existing);
                    }
                }

                writeBucket(bucket, record, now);
            } finally {
                unlockIfHeld(lock);
            }
        } catch (IdempotencyItemAlreadyExistsException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IdempotencyItemAlreadyExistsException("Interrupted while acquiring lock", e, null);
        } catch (Exception e) {
            LOG.error("Error while putting record for key: {}", key, e);
            throw new IdempotencyItemAlreadyExistsException("Error while putting record", e, null);
        }
    }

    @Override
    public void updateRecord(DataRecord record) {
        String key = storageKey(record.getIdempotencyKey());
        RLock lock = redissonClient.getLock(lockKey(key));

        try {
            boolean acquired = lock.tryLock(lockWaitTime, lockLeaseTime, TimeUnit.SECONDS);
            if (!acquired) {
                LOG.warn("Failed to acquire lock for update on key: {} within {} seconds", key, lockWaitTime);
            }

            try {
                RBucket<String> bucket = redissonClient.getBucket(key);
                writeBucket(bucket, record, Instant.now());
            } finally {
                unlockIfHeld(lock);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while acquiring update lock", e);
        } catch (Exception e) {
            LOG.error("Error while updating record for key: {}", key, e);
            throw new RuntimeException("Failed to update record", e);
        }
    }

    @Override
    public void deleteRecord(String idempotencyKey) {
        String key = storageKey(idempotencyKey);
        RLock lock = redissonClient.getLock(lockKey(key));

        try {
            boolean acquired = lock.tryLock(lockWaitTime, lockLeaseTime, TimeUnit.SECONDS);
            if (!acquired) {
                LOG.warn("Failed to acquire lock for delete on key: {} within {} seconds", key, lockWaitTime);
            }

            try {
                redissonClient.<String>getBucket(key).delete();
            } finally {
                unlockIfHeld(lock);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while acquiring delete lock", e);
        } catch (Exception e) {
            LOG.error("Error while deleting record for key: {}", key, e);
            throw new RuntimeException("Failed to delete record", e);
        }
    }

    private void writeBucket(RBucket<String> bucket, DataRecord record, Instant now) {
        String serialized = serialize(record);
        long ttl = record.getExpiryTimestamp() - now.getEpochSecond();
        if (ttl > 0) {
            bucket.set(serialized, ttl, TimeUnit.SECONDS);
        } else {
            bucket.set(serialized);
        }
    }

    private String storageKey(String idempotencyKey) {
        return keyPrefix + idempotencyKey;
    }

    private String lockKey(String storageKey) {
        return storageKey + LOCK_KEY_SUFFIX;
    }

    private void unlockIfHeld(RLock lock) {
        if (lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }

    private String serialize(DataRecord record) {
        try {
            return objectMapper.writeValueAsString(record);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize DataRecord", e);
        }
    }

    private DataRecord deserialize(String data) {
        try {
            return objectMapper.readValue(data, DataRecord.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize DataRecord", e);
        }
    }
}
