package com.im.core.retry;

import com.im.common.exception.ValidationException;
import com.im.common.retry.RetryConfig;
import com.im.common.retry.RetryExecutionException;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FailsafeRetryExecutorTest {

    @Test
    void businessExceptionIsPropagatedWithoutRetryWrapper() {
        FailsafeRetryExecutor executor = new FailsafeRetryExecutor();
        RetryConfig config = RetryConfig.builder()
                .maxAttempts(3)
                .fixedDelay(1)
                .build();
        ValidationException business = new ValidationException("bad request");
        AtomicInteger attempts = new AtomicInteger();

        ValidationException thrown = assertThrows(ValidationException.class,
                () -> executor.execute(config, () -> {
                    attempts.incrementAndGet();
                    throw business;
                }));

        assertSame(business, thrown);
        assertEquals(1, attempts.get());
    }

    @Test
    void retryableInfrastructureFailureIsWrappedAfterAttemptsExhausted() {
        FailsafeRetryExecutor executor = new FailsafeRetryExecutor();
        RetryConfig config = RetryConfig.builder()
                .maxAttempts(3)
                .fixedDelay(1)
                .build();
        AtomicInteger attempts = new AtomicInteger();

        RetryExecutionException thrown = assertThrows(RetryExecutionException.class,
                () -> executor.execute(config, () -> {
                    attempts.incrementAndGet();
                    throw new IllegalStateException("db timeout");
                }));

        assertEquals(3, attempts.get());
        assertEquals("db timeout", rootCause(thrown).getMessage());
    }

    private static Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
