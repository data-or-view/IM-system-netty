package com.im.api.retry;

/**
 * 重试事件监听器，在重试的各个阶段回调。
 *
 * <p>默认方法均为空实现，子类按需覆盖。</p>
 */
public interface RetryListener {

    /** 第 N 次重试即将执行（仅在 {@code attemptCount >= 2} 时调用） */
    default void onRetry(RetryEvent event) {}

    /** 重试成功（即最终执行成功） */
    default void onSuccess(RetryEvent event) {}

    /** 重试全部失败（即所有尝试均失败） */
    default void onFailure(RetryEvent event) {}
}
