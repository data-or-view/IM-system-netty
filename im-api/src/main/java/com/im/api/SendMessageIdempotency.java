package com.im.api;

import java.util.function.Supplier;

public interface SendMessageIdempotency {

    <T> T execute(String idempotencyKey, Supplier<T> action, Class<T> returnType);

    static SendMessageIdempotency none() {
        return new SendMessageIdempotency() {
            @Override
            public <T> T execute(String idempotencyKey, Supplier<T> action, Class<T> returnType) {
                return action.get();
            }
        };
    }
}
