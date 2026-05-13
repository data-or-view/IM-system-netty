package com.im.core.retry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 重试事件监听器，在重试的各个阶段回调。
 *
 * <p>默认方法均为空实现，子类按需覆盖。</p>
 *
 * <h3>内置监听器</h3>
 * <ul>
 *   <li>{@link #LOG_WARN} — 每次重试前打 warn 日志</li>
 *   <li>{@link #LOG_DEBUG} — 每次重试前打 debug 日志</li>
 * </ul>
 */
public interface RetryListener {

    /** 第 N 次重试即将执行（仅在 {@code attemptCount >= 2} 时调用） */
    default void onRetry(RetryEvent event) {}

    /** 重试成功（即最终执行成功） */
    default void onSuccess(RetryEvent event) {}

    /** 重试全部失败（即所有尝试均失败） */
    default void onFailure(RetryEvent event) {}

    // ── 预置监听器 ──

    RetryListener LOG_WARN = new RetryListener() {
        private final Logger log = LoggerFactory.getLogger("im.retry");
        @Override
        public void onRetry(RetryEvent event) {
            log.warn("Retry #{}, elapsed={}ms, lastError={}",
                    event.getAttemptCount(), event.getElapsedTimeMs(),
                    event.getLastError() != null ? event.getLastError().getMessage() : "unknown");
        }
        @Override
        public void onFailure(RetryEvent event) {
            log.error("Retry exhausted after {} attempts, {}ms: {}",
                    event.getAttemptCount(), event.getElapsedTimeMs(),
                    event.getLastError() != null ? event.getLastError().getMessage() : "unknown");
        }
    };

    RetryListener LOG_DEBUG = new RetryListener() {
        private final Logger log = LoggerFactory.getLogger("im.retry");
        @Override
        public void onRetry(RetryEvent event) {
            log.debug("Retry #{}, elapsed={}ms", event.getAttemptCount(), event.getElapsedTimeMs());
        }
    };
}
