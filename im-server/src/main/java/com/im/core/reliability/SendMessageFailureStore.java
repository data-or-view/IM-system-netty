package com.im.core.reliability;

import com.im.api.Message;

import java.util.List;

public interface SendMessageFailureStore {

    void recordFailure(String topic, Message message, Throwable cause);

    default List<MessageSendFailureRecord> findDueFailures(long nowMillis, int limit) {
        return List.of();
    }

    default void markReplayed(long id) {
    }

    default void markRetryLater(long id, int attemptCount, long nextRetryAt, Throwable cause) {
    }

    default void markFailed(long id, int attemptCount, Throwable cause) {
    }

    static SendMessageFailureStore none() {
        return (topic, message, cause) -> {
        };
    }
}
