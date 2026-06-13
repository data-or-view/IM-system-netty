package com.wzg.idempotency.exception;

/**
 * 幂等性状态不一致异常
 * 
 * <p>当持久化存储的状态在保存（put）和读取（get）操作之间发生变化时抛出此异常。
 * 这通常发生在并发场景下或记录过期时。</p>
 * 
 * <h3>抛出时机：</h3>
 * <ul>
 *   <li><strong>记录在保存和读取之间被删除：</strong>
 *       <ul>
 *         <li>saveInProgress 时记录存在（抛出 IdempotencyItemAlreadyExistsException）</li>
 *         <li>但在 getRecord 时记录不存在（抛出 IdempotencyItemNotFoundException）</li>
 *         <li>抛出位置：{@link com.wzg.idempotency.core.IdempotencyHandler#getIdempotencyRecord}</li>
 *         <li>错误消息："saveInProgress 和 getRecord 返回不一致的结果（记录在保存和读取之间被删除）"</li>
 *       </ul>
 *   </li>
 *   <li><strong>记录状态为 EXPIRED（已过期）：</strong>
 *       <ul>
 *         <li>理论上不应该发生，因为 saveInProgress 会检查过期时间</li>
 *         <li>如果发生，说明状态不一致（可能是并发操作导致）</li>
 *         <li>抛出位置：{@link com.wzg.idempotency.core.IdempotencyHandler#handleForStatus}</li>
 *         <li>错误消息："saveInProgress 和 getRecord 返回不一致的结果（记录状态为 EXPIRED）"</li>
 *       </ul>
 *   </li>
 *   <li><strong>INPROGRESS 状态已超时但状态仍为 INPROGRESS：</strong>
 *       <ul>
 *         <li>记录应该已经过期（INPROGRESS 状态已超时），但状态仍为 INPROGRESS</li>
 *         <li>抛出位置：{@link com.wzg.idempotency.core.IdempotencyHandler#handleForStatus}</li>
 *         <li>错误消息："记录应该已经过期（INPROGRESS 状态已超时），但状态仍为 INPROGRESS"</li>
 *       </ul>
 *   </li>
 * </ul>
 * 
 * <h3>处理机制：</h3>
 * <ul>
 *   <li>此异常会被 {@link com.wzg.idempotency.core.IdempotencyHandler#handle()} 方法捕获</li>
 *   <li>会进行重试（最多 5 次），因为状态不一致可能是暂时的（并发操作导致）</li>
 *   <li>如果重试后仍然不一致，会抛出异常</li>
 * </ul>
 * 
 * <h3>典型场景示例：</h3>
 * <pre>{@code
 * // 场景1：记录在保存和读取之间被删除
 * // 时间线：
 * // T1: 请求A调用 saveInProgress，记录存在，抛出 IdempotencyItemAlreadyExistsException
 * // T2: 记录过期或被手动删除
 * // T3: 请求A调用 getRecord，记录不存在，抛出 IdempotencyItemNotFoundException
 * // T4: 包装为 IdempotencyInconsistentStateException
 * 
 * // 场景2：并发操作导致状态不一致
 * // 时间线：
 * // T1: 请求A保存 INPROGRESS 状态
 * // T2: 请求B也尝试保存，发现记录存在
 * // T3: 请求A执行完成，保存 COMPLETED 状态
 * // T4: 请求B读取记录，发现状态为 COMPLETED（正常情况）
 * // T5: 但如果请求A执行失败，记录被删除，请求B读取时记录不存在（状态不一致）
 * }</pre>
 * 
 * <h3>为什么会出现状态不一致？</h3>
 * <ul>
 *   <li><strong>并发操作：</strong>多个请求同时操作同一条记录</li>
 *   <li><strong>记录过期：</strong>记录在操作过程中过期并被清理</li>
 *   <li><strong>手动删除：</strong>记录被手动删除（如清理操作）</li>
 *   <li><strong>存储层问题：</strong>持久化存储（如 Redis）的原子性问题</li>
 * </ul>
 * 
 * <h3>如何避免此异常？</h3>
 * <ul>
 *   <li>使用支持原子操作的持久化存储（如 Redis）</li>
 *   <li>合理设置过期时间，避免记录在操作过程中过期</li>
 *   <li>避免手动删除正在使用的记录</li>
 *   <li>使用事务或分布式锁来保证操作的原子性</li>
 * </ul>
 * 
 * @see com.wzg.idempotency.core.IdempotencyHandler
 * @see com.wzg.idempotency.persistence.DataRecord.Status
 */
public class IdempotencyInconsistentStateException extends RuntimeException {
    private static final long serialVersionUID = -4293951999802300672L;

    public IdempotencyInconsistentStateException(String msg, Exception e) {
        super(msg, e);
    }

    public IdempotencyInconsistentStateException(String msg) {
        super(msg);
    }
}
