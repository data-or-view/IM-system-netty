package com.im.api;

import java.util.List;

public interface BusinessMessageDlqStore {

    void recordFailure(String topic, Message message, Throwable cause);

    /**
     * Atomically claims due business-DLQ records for one compensator worker.
     *
     * <p>Production implementations must transition records from PENDING to
     * RETRYING with a conditional update so two cluster nodes cannot republish
     * the same record concurrently.</p>
     */
    default List<BusinessMessageDlqRecord> claimDueFailures(long nowMillis, int limit) {
        return List.of();
    }

    default List<BusinessMessageDlqRecord> claimDueFailures(long nowMillis, int limit, long leaseMillis) {
        return claimDueFailures(nowMillis, limit);
    }

    default List<BusinessMessageDlqRecord> findDueFailures(long nowMillis, int limit) {
        return List.of();
    }

    default void markRepublished(long id) {
    }

    default void markRetryLater(long id, int attemptCount, long nextRetryAt, Throwable cause) {
    }

    default void markFailed(long id, int attemptCount, Throwable cause) {
    }

    static BusinessMessageDlqStore none() {
        return (topic, message, cause) -> {
        };
    }
}
