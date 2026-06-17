package com.wzg.idempotency.exception;

/**
 * 幂等性配置异常
 * 
 * <p>当幂等性配置不正确或无法使用时抛出此异常。这通常发生在配置或执行幂等函数时。</p>
 * 
 * <h3>抛出时机：</h3>
 * <ul>
 *   <li><strong>没有配置持久化存储：</strong>
 *       <ul>
 *         <li>调用 {@code Idempotency.config().configure()} 前没有设置 persistence store</li>
 *         <li>幂等性需要持久化层保存 INPROGRESS 和 COMPLETED 记录</li>
 *       </ul>
 *   </li>
 *   <li><strong>幂等函数执行或序列化失败：</strong>
 *       <ul>
 *         <li>传入的 key 无法转换为 JSON payload</li>
 *         <li>返回值类型配置与缓存数据不匹配</li>
 *       </ul>
 *   </li>
 * </ul>
 * 
 * <h3>典型场景示例：</h3>
 * <pre>{@code
 * Idempotency.config()
 *     .withPersistenceStore(persistenceStore)
 *     .withConfig(IdempotencyConfig.builder().build())
 *     .configure();
 *
 * String result = Idempotency.makeIdempotent(
 *     "processOrder",
 *     order,
 *     () -> service.process(order),
 *     String.class);
 * }</pre>
 * 
 * <h3>如何避免此异常？</h3>
 * <ul>
 *   <li>确保调用 {@code configure()} 前设置了持久化存储</li>
 *   <li>确保幂等键对象可以被 Jackson 序列化</li>
 *   <li>确保幂等性配置正确（如持久化存储已配置）</li>
 * </ul>
 */
public class IdempotencyConfigurationException extends RuntimeException {
    private static final long serialVersionUID = 560587720373305487L;

    public IdempotencyConfigurationException(String message) {
        super(message);
    }
}
