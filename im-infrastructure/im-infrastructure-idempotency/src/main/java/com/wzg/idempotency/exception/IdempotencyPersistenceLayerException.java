package com.wzg.idempotency.exception;

/**
 * 幂等性持久化层异常
 * 
 * <p>当持久化存储层发生技术错误时抛出此异常。这通常表示与持久化存储（如 Redis）的通信问题或操作失败。</p>
 * 
 * <h3>抛出时机：</h3>
 * <ul>
 *   <li><strong>保存 INPROGRESS 状态失败：</strong>
 *       <ul>
 *         <li>调用 {@link com.wzg.idempotency.persistence.BasePersistenceStore#saveInProgress} 时</li>
 *         <li>持久化存储操作失败（如 Redis 连接失败、写入失败等）</li>
 *         <li>抛出位置：{@link com.wzg.idempotency.core.IdempotencyHandler#processIdempotency}</li>
 *         <li>错误消息："保存 INPROGRESS 状态到幂等性存储失败"</li>
 *       </ul>
 *   </li>
 *   <li><strong>获取记录失败：</strong>
 *       <ul>
 *         <li>调用 {@link com.wzg.idempotency.persistence.BasePersistenceStore#getRecord} 时</li>
 *         <li>持久化存储操作失败（如 Redis 连接失败、读取失败等）</li>
 *         <li>抛出位置：{@link com.wzg.idempotency.core.IdempotencyHandler#getIdempotencyRecord}</li>
 *         <li>错误消息："从幂等性存储获取记录失败"</li>
 *       </ul>
 *   </li>
 *   <li><strong>删除记录失败：</strong>
 *       <ul>
 *         <li>调用 {@link com.wzg.idempotency.persistence.BasePersistenceStore#deleteRecord} 时</li>
 *         <li>持久化存储操作失败（如 Redis 连接失败、删除失败等）</li>
 *         <li>抛出位置：{@link com.wzg.idempotency.core.IdempotencyHandler#getFunctionResponse}</li>
 *         <li>错误消息："从幂等性存储删除记录失败"</li>
 *       </ul>
 *   </li>
 *   <li><strong>保存成功状态失败：</strong>
 *       <ul>
 *         <li>调用 {@link com.wzg.idempotency.persistence.BasePersistenceStore#saveSuccess} 时</li>
 *         <li>持久化存储操作失败（如 Redis 连接失败、写入失败等）</li>
 *         <li>抛出位置：{@link com.wzg.idempotency.core.IdempotencyHandler#getFunctionResponse}</li>
 *         <li>错误消息："更新记录状态为成功失败"</li>
 *       </ul>
 *   </li>
 *   <li><strong>反序列化缓存结果失败：</strong>
 *       <ul>
 *         <li>从持久化存储读取记录后，反序列化响应数据失败</li>
 *         <li>可能是类型不匹配或 JSON 格式错误</li>
 *         <li>抛出位置：{@link com.wzg.idempotency.core.IdempotencyHandler#handleForStatus}</li>
 *         <li>错误消息："无法将函数响应反序列化为 {类型名}"</li>
 *       </ul>
 *   </li>
 *   <li><strong>等待执行完成时被中断：</strong>
 *       <ul>
 *         <li>在等待并发请求完成时，线程被中断</li>
 *         <li>抛出位置：{@link com.wzg.idempotency.core.IdempotencyHandler#handle}</li>
 *         <li>错误消息："等待执行完成时被中断"</li>
 *       </ul>
 *   </li>
 * </ul>
 * 
 * <h3>处理机制：</h3>
 * <ul>
 *   <li>此异常不会被重试，因为这是持久化存储的技术问题</li>
 *   <li>会直接抛出，让调用者处理</li>
 *   <li>通常需要检查持久化存储的连接和配置</li>
 * </ul>
 * 
 * <h3>典型场景示例：</h3>
 * <pre>{@code
 * // 场景1：Redis 连接失败
 * // Redis 服务器不可用或网络问题
 * // 抛出：IdempotencyPersistenceLayerException("保存 INPROGRESS 状态到幂等性存储失败", e)
 * 
 * // 场景2：反序列化失败
 * // 从 Redis 读取的记录格式不正确或类型不匹配
 * // 抛出：IdempotencyPersistenceLayerException("无法将函数响应反序列化为 OrderResponse", e)
 * 
 * // 场景3：线程中断
 * // 在等待并发请求完成时，线程被中断
 * // 抛出：IdempotencyPersistenceLayerException("等待执行完成时被中断", ie)
 * }</pre>
 * 
 * <h3>为什么会出现此异常？</h3>
 * <ul>
 *   <li><strong>持久化存储不可用：</strong>Redis 服务器不可用或网络问题</li>
 *   <li><strong>连接问题：</strong>无法连接到持久化存储</li>
 *   <li><strong>操作失败：</strong>持久化存储操作失败（如写入、读取、删除）</li>
 *   <li><strong>数据格式问题：</strong>存储的数据格式不正确，无法反序列化</li>
 *   <li><strong>类型不匹配：</strong>反序列化时类型不匹配</li>
 *   <li><strong>线程中断：</strong>等待操作时线程被中断</li>
 * </ul>
 * 
 * <h3>如何避免此异常？</h3>
 * <ul>
 *   <li>确保持久化存储（如 Redis）可用且可访问</li>
 *   <li>检查网络连接和防火墙配置</li>
 *   <li>确保持久化存储的配置正确（如连接地址、端口、密码等）</li>
 *   <li>确保存储的数据格式正确，可以被正确序列化和反序列化</li>
 *   <li>使用连接池和重试机制来提高可靠性</li>
 *   <li>监控持久化存储的健康状态</li>
 * </ul>
 * 
 * <h3>注意事项：</h3>
 * <ul>
 *   <li>此异常表示持久化存储的技术问题，不是业务逻辑问题</li>
 *   <li>应该检查持久化存储的连接和配置</li>
 *   <li>如果频繁出现，可能需要检查持久化存储的健康状态</li>
 *   <li>可以考虑使用重试机制或降级策略</li>
 * </ul>
 * 
 * @see com.wzg.idempotency.core.IdempotencyHandler
 * @see com.wzg.idempotency.persistence.BasePersistenceStore
 */
public class IdempotencyPersistenceLayerException extends RuntimeException {
    private static final long serialVersionUID = 6781832947434168547L;

    public IdempotencyPersistenceLayerException(String msg, Exception e) {
        super(msg, e);
    }
}
