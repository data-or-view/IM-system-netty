package com.im.common.retry;

/**
 * 重试事件，供 {@link RetryListener} 回调。
 */
public final class RetryEvent {

    private final int attemptCount;
    private final long elapsedTimeMs;
    private final Throwable lastError;

    public RetryEvent(int attemptCount, long elapsedTimeMs, Throwable lastError) {
        this.attemptCount = attemptCount;
        this.elapsedTimeMs = elapsedTimeMs;
        this.lastError = lastError;
    }

    public int getAttemptCount() { return attemptCount; }
    public long getElapsedTimeMs() { return elapsedTimeMs; }
    public Throwable getLastError() { return lastError; }
}
