package com.im.core.retry;

import java.util.concurrent.Callable;

/**
 * 重试执行器 —— 可替换的重试抽象接口。
 *
 * <p>通过此接口，业务代码不直接依赖任何重试实现库（Failsafe / spring-retry / 自写），
 * 更换实现只需在启动时换一个实例。</p>
 *
 * <h3>使用方法</h3>
 * <pre>{@code
 * // 使用预设策略
 * retryExecutor.execute(RetryStrategies.DB_WRITE, () -> {
 *     mapper.insert(entity);
 *     return null;   // void → 返回 null
 * });
 *
 * // 带恢复回调
 * String result = retryExecutor.execute(
 *     RetryStrategies.CRITICAL,
 *     () -> serverApi.call(),
 *     () -> fallbackValue          // 全部失败后的兜底
 * );
 * }</pre>
 *
 * @see RetryConfig
 * @see RetryStrategies
 */
@FunctionalInterface
public interface RetryExecutor {

    /**
     * 执行可重试操作。
     *
     * @param config   重试策略
     * @param callable 业务逻辑（每次重试重新执行）
     * @param <T>      返回值类型
     * @return 业务逻辑的返回值
     * @throws Exception 所有尝试均失败后抛出最后一次异常
     */
    <T> T execute(RetryConfig config, Callable<T> callable);

    /**
     * 执行可重试操作，带最终兜底。
     *
     * @param config    重试策略
     * @param callable  业务逻辑
     * @param recovery  所有尝试均失败后的回调（兜底）
     * @param <T>       返回值类型
     * @return 业务逻辑的返回值，或恢复回调的返回值
     */
    default <T> T execute(RetryConfig config, Callable<T> callable, Callable<T> recovery) {
        return execute(config, callable);
    }
}
