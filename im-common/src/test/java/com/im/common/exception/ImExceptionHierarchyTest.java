package com.im.common.exception;

import com.im.common.enums.ImErrorCode;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImExceptionHierarchyTest {

    @Test
    void legacyImExceptionRemainsClientVisibleBusinessException() {
        ImException ex = new ImException(ImErrorCode.BAD_REQUEST, "userId is required");

        assertEquals(ImErrorCode.BAD_REQUEST, ex.getErrorCode());
        assertEquals(ExceptionCategory.BUSINESS, ex.getCategory());
        assertEquals("userId is required", ex.getDetail());
        assertEquals("userId is required", ex.getSafeMessage());
        assertTrue(ex.isClientVisible());
    }

    @Test
    void businessExceptionIsClientVisibleAndKeepsAttributes() {
        BusinessException ex = new BusinessException(ImErrorCode.FORBIDDEN, "conversation not readable")
                .withAttribute("conversationId", "single_alice_bob");

        assertEquals(ExceptionCategory.BUSINESS, ex.getCategory());
        assertTrue(ex.isClientVisible());
        assertEquals("conversation not readable", ex.getSafeMessage());
        assertEquals(Map.of("conversationId", "single_alice_bob"), ex.getAttributes());
    }

    @Test
    void infrastructureExceptionHidesDetailFromClient() {
        InfrastructureException ex = new InfrastructureException(
                ImErrorCode.MQ_UNAVAILABLE, "redis xadd timeout", new RuntimeException("timeout"));

        assertEquals(ExceptionCategory.INFRASTRUCTURE, ex.getCategory());
        assertFalse(ex.isClientVisible());
        assertEquals("message queue unavailable", ex.getSafeMessage());
        assertEquals("redis xadd timeout", ex.getDetail());
    }

    @Test
    void persistenceExceptionsAreInfrastructureAndHideDetails() {
        PersistenceException ex = new DatabasePersistenceException(
                "insert im_messages failed", new RuntimeException("duplicate key"));

        assertEquals(ImErrorCode.INTERNAL_ERROR, ex.getErrorCode());
        assertEquals(ExceptionCategory.INFRASTRUCTURE, ex.getCategory());
        assertFalse(ex.isClientVisible());
        assertEquals("internal server error", ex.getSafeMessage());
        assertEquals("insert im_messages failed", ex.getDetail());
    }

    @Test
    void specializedPersistenceExceptionsArePersistenceExceptions() {
        assertTrue(new DatabasePersistenceException("db failed") instanceof PersistenceException);
        assertTrue(new RedisPersistenceException("redis failed") instanceof PersistenceException);
        assertTrue(new FileStorageException("storage failed") instanceof PersistenceException);
    }

    @Test
    void persistenceWrapperKeepsBusinessExceptionsUnchanged() {
        NotFoundException business = new NotFoundException("user missing");
        RuntimeException wrapped = new RuntimeException("retry exhausted",
                new RuntimeException("failsafe", business));

        assertEquals(business, PersistenceExceptions.database("select user", business));
        assertEquals(business, PersistenceExceptions.redis("lookup route", wrapped));
    }

    @Test
    void persistenceWrapperConvertsInfrastructureFailures() {
        RuntimeException cause = new RuntimeException("socket timeout");

        RuntimeException db = PersistenceExceptions.database("select im_messages", cause);
        RuntimeException redis = PersistenceExceptions.redis("hget route", cause);

        assertTrue(db instanceof DatabasePersistenceException);
        assertTrue(redis instanceof RedisPersistenceException);
        assertEquals("select im_messages failed", ((DatabasePersistenceException) db).getDetail());
        assertEquals("hget route failed", ((RedisPersistenceException) redis).getDetail());
        assertEquals(cause, db.getCause());
        assertEquals(cause, redis.getCause());
    }

    @Test
    void persistenceRunnerConvertsInfrastructureFailures() {
        RuntimeException cause = new RuntimeException("socket timeout");

        RuntimeException db = org.junit.jupiter.api.Assertions.assertThrows(DatabasePersistenceException.class,
                () -> PersistenceExceptions.runDatabase("select im_messages", () -> {
                    throw cause;
                }));
        RuntimeException redis = org.junit.jupiter.api.Assertions.assertThrows(RedisPersistenceException.class,
                () -> PersistenceExceptions.runRedis("hget route", () -> {
                    throw cause;
                }));

        assertEquals("select im_messages failed", ((DatabasePersistenceException) db).getDetail());
        assertEquals("hget route failed", ((RedisPersistenceException) redis).getDetail());
        assertEquals(cause, db.getCause());
        assertEquals(cause, redis.getCause());
    }

    @Test
    void persistenceRunnerKeepsBusinessExceptionsUnchanged() {
        NotFoundException business = new NotFoundException("user missing");

        NotFoundException thrown = org.junit.jupiter.api.Assertions.assertThrows(NotFoundException.class,
                () -> PersistenceExceptions.runDatabase("select user", () -> {
                    throw business;
                }));

        assertEquals(business, thrown);
    }

    @Test
    void persistenceRunnerReturnsActionResult() {
        assertEquals("ok", PersistenceExceptions.runDatabase("select user", () -> "ok"));
        assertEquals(1L, PersistenceExceptions.runRedis("incr seq", () -> 1L));
    }

    @Test
    void persistenceWrapperRestoresInterruptedFlag() {
        AtomicBoolean interrupted = new AtomicBoolean(false);
        Thread worker = new Thread(() -> {
            PersistenceExceptions.redis("get seq", new InterruptedException("interrupted"));
            interrupted.set(Thread.currentThread().isInterrupted());
        });

        worker.start();
        try {
            worker.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        assertTrue(interrupted.get());
    }

    @Test
    void persistenceRunnerRestoresInterruptedFlag() {
        AtomicBoolean interrupted = new AtomicBoolean(false);
        Thread worker = new Thread(() -> {
            org.junit.jupiter.api.Assertions.assertThrows(RedisPersistenceException.class,
                    () -> PersistenceExceptions.runRedis("get seq", () -> {
                        throw new InterruptedException("interrupted");
                    }));
            interrupted.set(Thread.currentThread().isInterrupted());
        });

        worker.start();
        try {
            worker.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        assertTrue(interrupted.get());
    }

    @Test
    void specializedBusinessExceptionsUseExpectedCodes() {
        assertEquals(ImErrorCode.BAD_REQUEST, new ValidationException("bad param").getErrorCode());
        assertEquals(ImErrorCode.UNAUTHORIZED, new UnauthorizedException("missing token").getErrorCode());
        assertEquals(ImErrorCode.FORBIDDEN, new ForbiddenException("blocked").getErrorCode());
        assertEquals(ImErrorCode.NOT_FOUND, new NotFoundException("missing").getErrorCode());
        assertEquals(ImErrorCode.CONFLICT, new ConflictException("duplicated").getErrorCode());
    }
}
