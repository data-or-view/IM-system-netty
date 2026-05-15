package com.im.common.retry;

import java.util.concurrent.Callable;

/**
 * 重试执行器 —— 可替换的重试抽象接口。
 *
 * <p>通过此接口，业务代码不直接依赖任何重试实现库。
 *
 * @see RetryConfig
 * @see RetryStrategies
 */
@FunctionalInterface
public interface RetryExecutor {

    <T> T execute(RetryConfig config, Callable<T> callable);

    default <T> T execute(RetryConfig config, Callable<T> callable, Callable<T> recovery) {
        return execute(config, callable);
    }
}
