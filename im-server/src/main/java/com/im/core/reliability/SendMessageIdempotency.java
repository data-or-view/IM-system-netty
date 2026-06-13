package com.im.core.reliability;

import java.util.function.Supplier;

public interface SendMessageIdempotency {

    <T> T execute(String idempotencyKey, Supplier<T> action, Class<T> returnType);

    static SendMessageIdempotency none() {
        return new NoopSendMessageIdempotency();
    }
}
