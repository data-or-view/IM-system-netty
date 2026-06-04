package com.im.common.retry;

import com.im.common.exception.ImException;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 重试策略配置 —— 纯数据对象，不绑定任何重试实现。
 */
public final class RetryConfig {

    private final int maxAttempts;
    private final long baseDelayMs;
    private final long maxDelayMs;
    private final double multiplier;
    private final double jitter;
    private final List<Class<? extends Throwable>> retryOn;
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

    public int getMaxAttempts() { return maxAttempts; }
    public long getBaseDelayMs() { return baseDelayMs; }
    public long getMaxDelayMs() { return maxDelayMs; }
    public double getMultiplier() { return multiplier; }
    public double getJitter() { return jitter; }
    public List<Class<? extends Throwable>> getRetryOn() { return retryOn; }
    public List<Class<? extends Throwable>> getAbortOn() { return abortOn; }

    public static Builder builder() { return new Builder(); }

    @SuppressWarnings("unchecked")
    public static final class Builder {
        private int maxAttempts = 3;
        private long baseDelayMs = 100;
        private long maxDelayMs = 2000;
        private double multiplier = 2.0;
        private double jitter = 0.2;
        private List<Class<? extends Throwable>> retryOn = List.of(Exception.class);
        private List<Class<? extends Throwable>> abortOn = List.of(ImException.class);

        public Builder maxAttempts(int val) {
            if (val < 1) throw new IllegalArgumentException("maxAttempts must be >= 1");
            this.maxAttempts = val;
            return this;
        }

        public Builder backoff(long baseMs, long maxMs, double factor) {
            if (baseMs <= 0) throw new IllegalArgumentException("baseMs must be > 0");
            if (maxMs < baseMs) throw new IllegalArgumentException("maxMs must be >= baseMs");
            if (factor <= 1.0) throw new IllegalArgumentException("factor must be > 1.0");
            this.baseDelayMs = baseMs;
            this.maxDelayMs = maxMs;
            this.multiplier = factor;
            return this;
        }

        public Builder fixedDelay(long delayMs) {
            if (delayMs <= 0) throw new IllegalArgumentException("delayMs must be > 0");
            this.baseDelayMs = delayMs;
            this.maxDelayMs = delayMs;
            this.multiplier = 1.0;
            return this;
        }

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
