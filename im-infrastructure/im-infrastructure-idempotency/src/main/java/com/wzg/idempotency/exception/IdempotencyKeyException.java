package com.wzg.idempotency.exception;

/**
 * 幂等性键异常
 * 
 * <p>当无法生成或提取幂等性键时抛出此异常。幂等性键用于唯一标识一个请求，相同的请求会生成相同的键。</p>
 * 
 * <h3>抛出时机：</h3>
 * <ul>
 *   <li><strong>无法从 payload 生成幂等性键：</strong>
 *       <ul>
 *         <li>payload 为空或 null</li>
 *         <li>无法从 payload 中提取数据来生成键</li>
 *         <li>抛出位置：{@link com.wzg.idempotency.persistence.BasePersistenceStore#getRecord}</li>
 *         <li>错误消息："No data found to create a hashed idempotency key"</li>
 *       </ul>
 *   </li>
 *   <li><strong>删除记录时无法生成键：</strong>
 *       <ul>
 *         <li>调用 {@link com.wzg.idempotency.persistence.BasePersistenceStore#deleteRecord} 时</li>
 *         <li>payload 为空或 null，无法生成键</li>
 *         <li>抛出位置：{@link com.wzg.idempotency.core.IdempotencyHandler#getFunctionResponse}</li>
 *       </ul>
 *   </li>
 * </ul>
 * 
 * <h3>处理机制：</h3>
 * <ul>
 *   <li>此异常不会被重试，因为这是配置或数据问题，不是临时错误</li>
 *   <li>会直接抛出，让调用者处理</li>
 *   <li>通常表示配置错误或数据格式不正确</li>
 * </ul>
 * 
 * <h3>幂等性键的生成方式：</h3>
 * <p>核心逻辑会把传入 {@code Idempotency.makeIdempotent(...)} 的 idempotencyKey 对象转换为 JSON，
 * 再对整个 JSON payload 计算哈希值。如果 key 为空且开启严格模式，则抛出此异常。</p>
 * 
 * <h3>典型场景示例：</h3>
 * <pre>{@code
 * // 场景1：幂等键为空
 * Idempotency.makeIdempotent("processOrder", null, () -> "success", String.class);
 * 
 * // 场景2：严格模式下幂等键为空
 * IdempotencyConfig.builder()
 *     .withThrowOnNoIdempotencyKey(true)
 *     .build();
 * }</pre>
 * 
 * <h3>如何避免此异常？</h3>
 * <ul>
 *   <li>确保传入 {@code makeIdempotent} 的 idempotencyKey 不为 null 或空</li>
 *   <li>确保 payload 不为 null 或空</li>
 *   <li>确保 payload 可以被序列化为 JSON</li>
 * </ul>
 * 
 * <h3>注意事项：</h3>
 * <ul>
 *   <li>此异常表示配置或数据问题，不是临时错误，不应该重试</li>
 *   <li>应该检查配置和数据结构，确保正确</li>
 * </ul>
 * 
 * @see com.wzg.idempotency.config.IdempotencyConfig
 * @see com.wzg.idempotency.persistence.BasePersistenceStore
 */
public class IdempotencyKeyException extends RuntimeException {
    private static final long serialVersionUID = -8514965705001281773L;

    public IdempotencyKeyException(String message) {
        super(message);
    }
}
