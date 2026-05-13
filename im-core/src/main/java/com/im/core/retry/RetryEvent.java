package com.im.core.retry;

/**
 * 重试事件，供 {@link RetryListener} 回调。
 *
 * <p>包含本次重试的尝试次数、已消耗时间和最后一次异常。</p>
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

    /** 第几次尝试（从 1 开始计数） */
    public int getAttemptCount() { return attemptCount; }

    /** 已耗费的总时间（毫秒） */
    public long getElapsedTimeMs() { return elapsedTimeMs; }

    /** 最近一次失败的异常（首次成功时为 null） */
    public Throwable getLastError() { return lastError; }
}
