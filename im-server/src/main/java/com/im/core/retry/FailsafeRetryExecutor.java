package com.im.core.retry;

import com.im.common.retry.RetryConfig;
import com.im.common.retry.RetryEvent;
import com.im.common.retry.RetryExecutionException;
import com.im.common.retry.RetryExecutor;
import com.im.common.retry.RetryListener;
import com.im.common.exception.ImException;
import dev.failsafe.Failsafe;
import dev.failsafe.Fallback;
import dev.failsafe.RetryPolicy;
import dev.failsafe.RetryPolicyBuilder;
import dev.failsafe.function.CheckedSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * {@link RetryExecutor} 的 Failsafe 实现。
 *
 * <p>零外部依赖（Failsafe 本身无传递依赖），纯 JDK 实现。
 * 将 {@link RetryConfig} 翻译为 Failsafe 的 {@link RetryPolicy} 后执行。</p>
 *
 * <p>Failsafe 默认将所有异常包装为 {@link dev.failsafe.FailsafeException} (RuntimeException)，
 * 本实现不做额外包装，直接抛出。</p>
 */
public class FailsafeRetryExecutor implements RetryExecutor {

    private static final Logger log = LoggerFactory.getLogger(FailsafeRetryExecutor.class);

    private static final RetryListener DEFAULT_LISTENER = new RetryListener() {
        @Override
        public void onRetry(RetryEvent event) {
            if (event.getLastError() != null) {
                log.warn("Retry attempt {} failed: {}", event.getAttemptCount(), event.getLastError().getMessage());
            }
        }

        @Override
        public void onFailure(RetryEvent event) {
            log.warn("Retry exhausted after {} attempts ({}ms)", event.getAttemptCount(), event.getElapsedTimeMs());
        }
    };

    private final RetryListener listener;

    public FailsafeRetryExecutor() {
        this(DEFAULT_LISTENER);
    }

    public FailsafeRetryExecutor(RetryListener listener) {
        this.listener = listener;
    }

    @Override
    public <T> T execute(RetryConfig config, Callable<T> callable) {
        RetryPolicy<T> policy = buildPolicy(config);
        long start = System.currentTimeMillis();

        try {
            return Failsafe.with(policy)
                    .get(checked(callable));
        } catch (RuntimeException e) {
            ImException business = findBusinessException(e);
            if (business != null) {
                throw business;
            }
            // 重试耗尽后抛 FailsafeException，保留原始异常原因
            throw new RetryExecutionException(
                    "Retry exhausted after " + config.getMaxAttempts()
                            + " attempts, " + (System.currentTimeMillis() - start) + "ms",
                    e);
        }
    }

    @Override
    public <T> T execute(RetryConfig config, Callable<T> callable, Callable<T> recovery) {
        RetryPolicy<T> policy = buildPolicy(config);
        Fallback<T> fallback = Fallback.of(() -> recovery.call());
        long start = System.currentTimeMillis();

        try {
            // fallback 在最外层，retryPolicy 在内层
            return Failsafe.with(fallback, policy)
                    .get(checked(callable));
        } catch (RuntimeException e) {
            ImException business = findBusinessException(e);
            if (business != null) {
                throw business;
            }
            // fallback 本身也可能抛异常
            throw new RetryExecutionException(
                    "Retry exhausted with recovery failed after " + config.getMaxAttempts()
                            + " attempts, " + (System.currentTimeMillis() - start) + "ms",
                    e);
        }
    }

    // ── 策略构建 ──

    @SuppressWarnings("unchecked")
    private <T> RetryPolicy<T> buildPolicy(RetryConfig config) {
        RetryPolicyBuilder<T> builder = RetryPolicy.<T>builder();

        // 最大尝试次数（包含第一次）
        builder.withMaxAttempts(config.getMaxAttempts());

        // 退避策略
        if (config.getMaxDelayMs() > config.getBaseDelayMs() && config.getMultiplier() > 1.0) {
            builder.withBackoff(
                    config.getBaseDelayMs(), config.getMaxDelayMs(),
                    java.time.temporal.ChronoUnit.MILLIS, config.getMultiplier());
        } else {
            builder.withDelay(Duration.ofMillis(config.getBaseDelayMs()));
        }

        // 随机抖动
        if (config.getJitter() > 0) {
            builder.withJitter(config.getJitter());
        }

        // retryOn —— Failsafe 默认所有异常都重试，所以只限制到指定类型
        List<Class<? extends Throwable>> retryOn = config.getRetryOn();
        if (retryOn != null && !retryOn.isEmpty()) {
            builder.handle(retryOn.toArray(new Class[0]));
        }

        // abortOn —— 不重试的异常
        List<Class<? extends Throwable>> abortOn = config.getAbortOn();
        if (abortOn != null && !abortOn.isEmpty()) {
            builder.abortOn(abortOn.toArray(new Class[0]));
        }

        // ── 事件监听 ──
        builder.onRetry(e -> {
            listener.onRetry(new RetryEvent(
                    e.getAttemptCount(),
                    e.getElapsedTime().toMillis(),
                    e.getLastException()));
        });
        builder.onSuccess(e -> {
            listener.onSuccess(new RetryEvent(
                    e.getAttemptCount(),
                    e.getElapsedTime().toMillis(),
                    null));
        });
        // Failsafe 的 onRetriesExceeded ≈ 我们的 onFailure
        builder.onRetriesExceeded(e -> {
            listener.onFailure(new RetryEvent(
                    e.getAttemptCount(),
                    e.getElapsedTime().toMillis(),
                    e.getException()));
        });

        return builder.build();
    }

    /** 将 Callable 转为 Failsafe CheckedSupplier */
    private static <T> CheckedSupplier<T> checked(Callable<T> callable) {
        return () -> {
            try {
                return callable.call();
            } catch (Exception e) {
                throw e;
            } catch (Throwable t) {
                // Callable 只抛 Exception，理论上不会到这里
                throw new RuntimeException(t);
            }
        };
    }

    private static ImException findBusinessException(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof ImException imException) {
                return imException;
            }
            current = current.getCause();
        }
        return null;
    }
}
