package com.wzg.idempotency.core;

/**
 * Functional interface for idempotent function execution.
 * This interface is similar to Callable but throws Throwable instead of Exception.
 * This is necessary to support AspectJ's ProceedingJoinPoint.proceed() which throws Throwable.
 *
 * @param <T> the return type of the function
 */
@FunctionalInterface
public interface IdempotentFunction<T> {
    @SuppressWarnings("java:S112")
    T execute() throws Throwable;
}
