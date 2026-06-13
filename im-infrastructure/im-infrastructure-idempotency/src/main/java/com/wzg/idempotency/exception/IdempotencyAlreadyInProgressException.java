package com.wzg.idempotency.exception;

/**
 * 幂等性正在执行中异常
 * 
 * <p>当相同的请求正在执行中时抛出此异常。这通常发生在高并发场景下。</p>
 * 
 * <h3>抛出时机：</h3>
 * <ul>
 *   <li>当多个相同的请求同时到达时，第一个请求会成功保存 INPROGRESS（执行中）状态并开始执行</li>
 *   <li>其他请求发现记录已存在且状态为 INPROGRESS 时，会抛出此异常</li>
 *   <li>这是并发场景下的正常情况，不是错误</li>
 * </ul>
 * 
 * <h3>处理机制：</h3>
 * <ul>
 *   <li>此异常会被 {@link com.wzg.idempotency.core.IdempotencyHandler#handle()} 方法捕获</li>
 *   <li>使用指数退避策略进行重试（10ms, 20ms, 40ms, 80ms, 160ms）</li>
 *   <li>最大重试次数为 5 次</li>
 *   <li>重试的目的是等待第一个请求完成，然后直接返回缓存的结果</li>
 * </ul>
 * 
 * <h3>典型场景示例：</h3>
 * <pre>{@code
 * // 场景：用户快速点击提交按钮两次
 * // 第一次请求：保存 INPROGRESS 状态，开始执行支付逻辑
 * // 第二次请求：发现记录状态为 INPROGRESS，抛出此异常
 * // 处理：等待 10ms 后重试，如果第一次请求已完成，直接返回缓存结果
 * }</pre>
 * 
 * <h3>为什么需要这个异常？</h3>
 * <ul>
 *   <li>防止重复执行：确保相同的请求只执行一次</li>
 *   <li>支持并发：允许并发请求等待第一个请求完成</li>
 *   <li>提高效率：后续请求可以直接返回缓存结果，无需重新执行</li>
 * </ul>
 * 
 * @see com.wzg.idempotency.core.IdempotencyHandler
 * @see com.wzg.idempotency.persistence.DataRecord.Status#INPROGRESS
 */
public class IdempotencyAlreadyInProgressException extends RuntimeException {
    private static final long serialVersionUID = 7229475093418832265L;

    public IdempotencyAlreadyInProgressException(String msg) {
        super(msg);
    }
}
