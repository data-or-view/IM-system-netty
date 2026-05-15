package com.im.common.retry;

/**
 * 重试事件监听器，在重试的各个阶段回调。
 */
public interface RetryListener {

    default void onRetry(RetryEvent event) {}
    default void onSuccess(RetryEvent event) {}
    default void onFailure(RetryEvent event) {}
}
