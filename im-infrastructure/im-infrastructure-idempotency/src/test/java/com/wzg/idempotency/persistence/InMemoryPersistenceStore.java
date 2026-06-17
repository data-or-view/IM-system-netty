package com.wzg.idempotency.persistence;

import com.wzg.idempotency.exception.IdempotencyItemAlreadyExistsException;
import com.wzg.idempotency.exception.IdempotencyItemNotFoundException;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryPersistenceStore extends BasePersistenceStore {
    private final Map<String, DataRecord> data = new ConcurrentHashMap<>();

    @Override
    public DataRecord getRecord(String idempotencyKey) throws IdempotencyItemNotFoundException {
        DataRecord dr = data.get(idempotencyKey);
        if (dr == null) {
            throw new IdempotencyItemNotFoundException(idempotencyKey);
        }
        return dr;
    }

    @Override
    public void putRecord(DataRecord dr, Instant now) throws IdempotencyItemAlreadyExistsException {
        if (data.containsKey(dr.getIdempotencyKey())) {
            DataRecord existing = data.get(dr.getIdempotencyKey());
            if (!existing.isExpired(now)) {
                throw new IdempotencyItemAlreadyExistsException("Record already exists", null, existing);
            }
        }
        data.put(dr.getIdempotencyKey(), dr);
    }

    @Override
    public void updateRecord(DataRecord dr) {
        data.put(dr.getIdempotencyKey(), dr);
    }

    @Override
    public void deleteRecord(String idempotencyKey) {
        data.remove(idempotencyKey);
    }
}
