package com.wzg.idempotency.exception;

import java.util.Optional;
import com.wzg.idempotency.persistence.DataRecord;

/**
 * 幂等性记录已存在异常
 * 
 * <p>当尝试保存一条已存在的幂等性记录时抛出此异常。这通常发生在重复请求或并发场景下。</p>
 * 
 * <h3>抛出时机：</h3>
 * <ul>
 *   <li><strong>保存 INPROGRESS 状态时记录已存在：</strong>
 *       <ul>
 *         <li>调用 {@link com.wzg.idempotency.persistence.BasePersistenceStore#saveInProgress} 时</li>
 *         <li>记录已存在且未过期（状态为 COMPLETED 或 INPROGRESS）</li>
 *         <li>抛出位置：{@link com.wzg.idempotency.persistence.BasePersistenceStore#saveInProgress}</li>
 *         <li>抛出位置：{@link com.wzg.idempotency.persistence.RedisPersistenceStore#putRecord}</li>
 *         <li>抛出位置：{@link com.wzg.idempotency.persistence.RedissonPersistenceStore#putRecord}</li>
 *         <li>测试替身存储也必须遵循同样的异常语义</li>
 *       </ul>
 *   </li>
 * </ul>
 * 
 * <h3>处理机制：</h3>
 * <ul>
 *   <li>此异常会被 {@link com.wzg.idempotency.core.IdempotencyHandler#processIdempotency} 方法捕获</li>
 *   <li>会尝试从异常中获取已存在的记录（通过 {@link #getDataRecord()} 方法）</li>
 *   <li>如果无法从异常中获取，会调用 {@link com.wzg.idempotency.persistence.BasePersistenceStore#getRecord} 获取</li>
 *   <li>根据记录的状态进行处理：
 *       <ul>
 *         <li>COMPLETED：返回缓存的结果</li>
 *         <li>INPROGRESS：抛出 {@link IdempotencyAlreadyInProgressException}（会触发重试）</li>
 *         <li>EXPIRED：重新执行（理论上不应该发生）</li>
 *       </ul>
 *   </li>
 * </ul>
 * 
 * <h3>异常中包含的数据记录：</h3>
 * <ul>
 *   <li>此异常可能包含已存在的 {@link DataRecord}（通过 {@link #getDataRecord()} 方法获取）</li>
 *   <li>如果包含记录，可以直接使用，避免再次查询持久化存储</li>
 *   <li>如果不包含记录，需要调用 {@link com.wzg.idempotency.persistence.BasePersistenceStore#getRecord} 获取</li>
 * </ul>
 * 
 * <h3>典型场景示例：</h3>
 * <pre>{@code
 * // 场景1：重复请求
 * // 用户提交订单后，由于网络问题，再次点击提交按钮
 * // 第一次请求：成功保存 INPROGRESS 状态，开始执行
 * // 第二次请求：尝试保存 INPROGRESS 状态，记录已存在，抛出此异常
 * // 处理：获取已存在的记录，如果状态为 COMPLETED，返回缓存结果
 * 
 * // 场景2：并发请求
 * // 多个相同的请求同时到达
 * // 第一个请求：成功保存 INPROGRESS 状态
 * // 其他请求：尝试保存 INPROGRESS 状态，记录已存在，抛出此异常
 * // 处理：获取已存在的记录，如果状态为 INPROGRESS，抛出 IdempotencyAlreadyInProgressException
 * }</pre>
 * 
 * <h3>为什么需要这个异常？</h3>
 * <ul>
 *   <li><strong>幂等性保证：</strong>确保相同的请求只执行一次</li>
 *   <li><strong>性能优化：</strong>如果记录已存在，可以直接返回缓存结果，无需重新执行</li>
 *   <li><strong>状态管理：</strong>通过记录的状态来判断如何处理重复请求</li>
 * </ul>
 * 
 * <h3>注意事项：</h3>
 * <ul>
 *   <li>此异常不是错误，而是幂等性机制的正常行为</li>
 *   <li>应该根据记录的状态来决定如何处理（返回缓存结果或等待）</li>
 *   <li>不要忽略此异常，否则可能导致重复执行</li>
 * </ul>
 * 
 * @see com.wzg.idempotency.core.IdempotencyHandler
 * @see com.wzg.idempotency.persistence.BasePersistenceStore
 * @see com.wzg.idempotency.persistence.DataRecord
 */
public class IdempotencyItemAlreadyExistsException extends RuntimeException {
    private static final long serialVersionUID = 9027152772149436500L;
    private transient Optional<DataRecord> dr = Optional.empty();

    public IdempotencyItemAlreadyExistsException() {
        super();
    }

    public IdempotencyItemAlreadyExistsException(String msg, Throwable e) {
        super(msg, e);
    }

    public IdempotencyItemAlreadyExistsException(String msg, Throwable e, DataRecord dr) {
        super(msg, e);
        this.dr = Optional.ofNullable(dr);
    }

    public Optional<DataRecord> getDataRecord() {
        return dr;
    }
}
