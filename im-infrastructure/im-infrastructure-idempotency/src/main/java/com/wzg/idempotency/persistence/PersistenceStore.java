package com.wzg.idempotency.persistence;

import com.wzg.idempotency.exception.IdempotencyItemAlreadyExistsException;
import com.wzg.idempotency.exception.IdempotencyItemNotFoundException;

import java.time.Instant;

/**
 * Persistence layer that will store the idempotency result.
 */
public interface PersistenceStore {

    /**
     * Retrieve item from persistence store using idempotency key.
     *
     * @param idempotencyKey the key of the record
     * @return DataRecord representation of existing record found in persistence store
     * @throws IdempotencyItemNotFoundException if no record exists
     */
    DataRecord getRecord(String idempotencyKey) throws IdempotencyItemNotFoundException;

    /**
     * Add a DataRecord to persistence store if it does not already exist with that key.
     *
     * @param record DataRecord instance
     * @param now current time
     * @throws IdempotencyItemAlreadyExistsException if a non-expired entry already exists
     */
    void putRecord(DataRecord record, Instant now) throws IdempotencyItemAlreadyExistsException;

    /**
     * Update item in persistence store.
     *
     * @param record DataRecord instance
     */
    void updateRecord(DataRecord record);

    /**
     * Remove item from persistence store.
     *
     * @param idempotencyKey the key of the record
     */
    void deleteRecord(String idempotencyKey);
}
