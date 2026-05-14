package com.im.api.retry;

import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 重试策略配置 —— 纯数据对象，不绑定任何重试实现。
 *
 * <p>使用 Builder 构造：</p>
 * <pre>{@code
 * RetryConfig config = RetryConfig.builder()
 *     .maxAttempts(3)
 *     .backoff(100, 2000, 2.0)   // 100ms → 200ms → 400ms → ... → 上限 2s
 *     .jitter(0.2)                // ±20% 随机抖动
 *     .retryOn(DataAccessException.class)
 *     .abortOn(DataIntegrityViolationException.class)
 *     .build();
 * }</pre>
 */
public final class RetryConfig {

    /** 最大尝试次数（包含第一次） */
    private final int maxAttempts;

    /** 初始退避延迟，毫秒 */
    private final long baseDelayMs;

    /** 最大退避延迟，毫秒（指数增长的上限） */
    private final long maxDelayMs;

    /** 指数退避倍数 */
    private final double multiplier;

    /** 随机抖动的比例 (0.0 ~ 1.0) */
    private final double jitter;

    /** 需要重试的异常类型（子类也匹配） */
    private final List<Class<? extends Throwable>> retryOn;

    /** 不需要重试的异常类型（优先级高于 retryOn） */
    private final List<Class<? extends Throwable>> abortOn;

    private RetryConfig(Builder b) {
        this.maxAttempts = b.maxAttempts;
        this.baseDelayMs = b.baseDelayMs;
        this.maxDelayMs = b.maxDelayMs;
        this.multiplier = b.multiplier;
        this.jitter = b.jitter;
        this.retryOn = Collections.unmodifiableList(b.retryOn);
        this.abortOn = Collections.unmodifiableList(b.abortOn);
    }

    // ── getters ──

    public int getMaxAttempts() { return maxAttempts; }
    public long getBaseDelayMs() { return baseDelayMs; }
    public long getMaxDelayMs() { return maxDelayMs; }
    public double getMultiplier() { return multiplier; }
    public double getJitter() { return jitter; }
    public List<Class<? extends Throwable>> getRetryOn() { return retryOn; }
    public List<Class<? extends Throwable>> getAbortOn() { return abortOn; }

    // ── Builder ──

    public static Builder builder() { return new Builder(); }

    @SuppressWarnings("unchecked")
    public static final class Builder {
        private int maxAttempts = 3;
        private long baseDelayMs = 100;
        private long maxDelayMs = 2000;
        private double multiplier = 2.0;
        private double jitter = 0.2;
        private List<Class<? extends Throwable>> retryOn = List.of(Exception.class);
        private List<Class<? extends Throwable>> abortOn = List.of();

        public Builder maxAttempts(int val) {
            if (val < 1) throw new IllegalArgumentException("maxAttempts must be >= 1");
            this.maxAttempts = val;
            return this;
        }

        /**
         * 指数退避配置。
         *
         * @param baseMs    初始延迟（毫秒）
         * @param maxMs     最大延迟上限（毫秒）
         * @param factor    指数倍数（>1.0）
         */
        public Builder backoff(long baseMs, long maxMs, double factor) {
            if (baseMs <= 0) throw new IllegalArgumentException("baseMs must be > 0");
            if (maxMs < baseMs) throw new IllegalArgumentException("maxMs must be >= baseMs");
            if (factor <= 1.0) throw new IllegalArgumentException("factor must be > 1.0");
            this.baseDelayMs = baseMs;
            this.maxDelayMs = maxMs;
            this.multiplier = factor;
            return this;
        }

        /**
         * 固定延迟（无指数增长）。
         */
        public Builder fixedDelay(long delayMs) {
            if (delayMs <= 0) throw new IllegalArgumentException("delayMs must be > 0");
            this.baseDelayMs = delayMs;
            this.maxDelayMs = delayMs;
            this.multiplier = 1.0;
            return this;
        }

        /**
         * 随机抖动比例（0.0 ~ 1.0）。
         * 例如 0.2 表示实际延迟 = 计算延迟 × (0.8 ~ 1.2)
         */
        public Builder jitter(double val) {
            if (val < 0 || val > 1.0) throw new IllegalArgumentException("jitter must be [0, 1.0]");
            this.jitter = val;
            return this;
        }

        @SafeVarargs
        public final Builder retryOn(Class<? extends Throwable>... types) {
            this.retryOn = Arrays.asList(types);
            return this;
        }

        @SafeVarargs
        public final Builder abortOn(Class<? extends Throwable>... types) {
            this.abortOn = Arrays.asList(types);
            return this;
        }

        public RetryConfig build() {
            return new RetryConfig(this);
        }
    }

    @Override
    public String toString() {
        return "RetryConfig{attempts=" + maxAttempts
                + ", backoff=[" + baseDelayMs + "→" + maxDelayMs + " x" + multiplier + "]"
                + ", jitter=" + jitter
                + ", retryOn=" + retryOn.size() + " types"
                + ", abortOn=" + abortOn.size() + " types}";
    }
}
