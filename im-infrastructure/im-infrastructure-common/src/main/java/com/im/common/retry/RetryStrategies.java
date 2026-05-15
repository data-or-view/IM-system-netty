package com.im.common.retry;

/**
 * 预设重试策略常量。
 */
public final class RetryStrategies {

    private RetryStrategies() {}

    /**
     * DB 写入：3 次重试，100ms → 200ms → 400ms（上限 2s），±20% 抖动。
     */
    public static final RetryConfig DB_WRITE = RetryConfig.builder()
            .maxAttempts(3)
            .backoff(100, 2000, 2.0)
            .jitter(0.2)
            .retryOn(Exception.class)
            .build();

    /**
     * 消息存储：3 次重试，50ms → 100ms → 200ms（上限 1s），±10% 抖动。
     */
    public static final RetryConfig MESSAGE_STORE = RetryConfig.builder()
            .maxAttempts(3)
            .backoff(50, 1000, 2.0)
            .jitter(0.1)
            .retryOn(Exception.class)
            .build();

    /**
     * 外部 API 调用：2 次重试，200ms 固定延迟。
     */
    public static final RetryConfig QUICK = RetryConfig.builder()
            .maxAttempts(2)
            .fixedDelay(200)
            .retryOn(Exception.class)
            .build();

    /**
     * 关键路径：5 次重试，100ms → 200ms → 400ms → 800ms → 1.6s（上限 3s），±30% 抖动。
     */
    public static final RetryConfig CRITICAL = RetryConfig.builder()
            .maxAttempts(5)
            .backoff(100, 3000, 2.0)
            .jitter(0.3)
            .retryOn(Exception.class)
            .build();
}
