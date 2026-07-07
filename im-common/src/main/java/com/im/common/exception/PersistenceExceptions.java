package com.im.common.exception;

import java.util.concurrent.Callable;

/**
 * Factory helpers for wrapping low-level persistence failures while preserving business exceptions.
 */
public final class PersistenceExceptions {
    private PersistenceExceptions() {
    }

    public static RuntimeException database(String operation, Throwable cause) {
        return wrap(operation, cause, true);
    }

    public static RuntimeException redis(String operation, Throwable cause) {
        return wrap(operation, cause, false);
    }

    public static <T> T runDatabase(String operation, Callable<T> action) {
        return run(operation, action, true);
    }

    public static <T> T runRedis(String operation, Callable<T> action) {
        return run(operation, action, false);
    }

    private static <T> T run(String operation, Callable<T> action, boolean database) {
        try {
            return action.call();
        } catch (Exception e) {
            throw database ? database(operation, e) : redis(operation, e);
        }
    }

    private static RuntimeException wrap(String operation, Throwable cause, boolean database) {
        ImException business = findBusinessException(cause);
        if (business != null) {
            return business;
        }
        if (cause instanceof InterruptedException) {
            Thread.currentThread().interrupt();
        }
        String detail = normalize(operation) + " failed";
        return database
                ? new DatabasePersistenceException(detail, cause)
                : new RedisPersistenceException(detail, cause);
    }

    private static ImException findBusinessException(Throwable cause) {
        Throwable current = cause;
        while (current != null) {
            if (current instanceof ImException imException && !(current instanceof PersistenceException)) {
                return imException;
            }
            current = current.getCause();
        }
        return null;
    }

    private static String normalize(String operation) {
        if (operation == null || operation.isBlank()) {
            return "persistence operation";
        }
        return operation;
    }
}
