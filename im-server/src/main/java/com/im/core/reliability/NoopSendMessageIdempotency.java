package com.im.core.reliability;

import java.util.function.Supplier;

final class NoopSendMessageIdempotency implements SendMessageIdempotency {

    @Override
    public <T> T execute(String idempotencyKey, Supplier<T> action, Class<T> returnType) {
        return action.get();
    }
}
