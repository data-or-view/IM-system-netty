package com.wzg.idempotency.exception;

/**
 * 幂等性记录未找到异常
 * 
 * <p>当在持久化存储中找不到指定的幂等性记录时抛出此异常。这通常发生在记录不存在、已过期或被删除时。</p>
 * 
 * <h3>抛出时机：</h3>
 * <ul>
 *   <li><strong>获取记录时记录不存在：</strong>
 *       <ul>
 *         <li>调用 {@link com.wzg.idempotency.persistence.BasePersistenceStore#getRecord} 时</li>
 *         <li>记录不存在或已过期</li>
 *         <li>抛出位置：{@link com.wzg.idempotency.persistence.BasePersistenceStore#getRecord}</li>
 *         <li>抛出位置：{@link com.wzg.idempotency.persistence.RedisPersistenceStore#getRecord}</li>
 *         <li>抛出位置：{@link com.wzg.idempotency.persistence.RedissonPersistenceStore#getRecord}</li>
 *         <li>抛出位置：{@link com.wzg.idempotency.persistence.InMemoryPersistenceStore#getRecord}</li>
 *       </ul>
 *   </li>
 * </ul>
 * 
 * <h3>处理机制：</h3>
 * <ul>
 *   <li>此异常会被 {@link com.wzg.idempotency.core.IdempotencyHandler#getIdempotencyRecord} 方法捕获</li>
 *   <li>会包装为 {@link IdempotencyInconsistentStateException}，因为：
 *       <ul>
 *         <li>saveInProgress 时记录存在（抛出 IdempotencyItemAlreadyExistsException）</li>
 *         <li>但在 getRecord 时记录不存在（抛出 IdempotencyItemNotFoundException）</li>
 *         <li>这说明状态不一致，可能是并发操作或记录过期导致</li>
 *       </ul>
 *   </li>
 *   <li>包装后的异常会被 {@link com.wzg.idempotency.core.IdempotencyHandler#handle} 方法捕获并重试</li>
 * </ul>
 * 
 * <h3>典型场景示例：</h3>
 * <pre>{@code
 * // 场景1：记录不存在（第一次请求）
 * // 用户提交订单，这是第一次请求
 * // saveInProgress：记录不存在，成功保存 INPROGRESS 状态
 * // 这是正常情况，不会抛出此异常
 * 
 * // 场景2：记录已过期
 * // 用户提交订单，但记录已过期
 * // getRecord：记录不存在（已过期），抛出此异常
 * // 处理：包装为 IdempotencyInconsistentStateException，然后重新执行
 * 
 * // 场景3：记录在保存和读取之间被删除
 * // 时间线：
 * // T1: saveInProgress 时记录存在，抛出 IdempotencyItemAlreadyExistsException
 * // T2: 记录被删除（过期或手动删除）
 * // T3: getRecord 时记录不存在，抛出此异常
 * // T4: 包装为 IdempotencyInconsistentStateException
 * }</pre>
 * 
 * <h3>为什么会出现记录不存在？</h3>
 * <ul>
 *   <li><strong>第一次请求：</strong>记录确实不存在，这是正常情况</li>
 *   <li><strong>记录过期：</strong>记录已过期并被清理</li>
 *   <li><strong>手动删除：</strong>记录被手动删除（如清理操作）</li>
 *   <li><strong>并发操作：</strong>记录在保存和读取之间被删除</li>
 *   <li><strong>存储层问题：</strong>持久化存储（如 Redis）的问题</li>
 * </ul>
 * 
 * <h3>注意事项：</h3>
 * <ul>
 *   <li>第一次请求时记录不存在是正常情况，不会抛出此异常</li>
 *   <li>如果 saveInProgress 时记录存在，但 getRecord 时记录不存在，说明状态不一致</li>
 *   <li>应该根据业务场景来决定如何处理（重新执行或返回错误）</li>
 * </ul>
 * 
 * @see com.wzg.idempotency.core.IdempotencyHandler
 * @see com.wzg.idempotency.persistence.BasePersistenceStore
 * @see IdempotencyInconsistentStateException
 */
public class IdempotencyItemNotFoundException extends RuntimeException {
    private static final long serialVersionUID = 4818288566747993032L;

    public IdempotencyItemNotFoundException(String idempotencyKey) {
        super("Item with idempotency key " + idempotencyKey + " not found");
    }
}
