package com.wzg.idempotency.persistence;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * 幂等性数据记录类
 * 
 * <p>这是存储在持久化层（如 Redis、内存等）中的幂等性记录数据结构。
 * 每个记录代表一个请求的幂等性状态和结果。</p>
 * 
 * <h3>记录的生命周期：</h3>
 * <ol>
 *   <li><strong>INPROGRESS（执行中）：</strong>请求开始执行时创建，保存执行中状态</li>
 *   <li><strong>COMPLETED（已完成）：</strong>请求执行成功后更新，保存执行结果</li>
 *   <li><strong>EXPIRED（已过期）：</strong>记录过期后自动变为过期状态</li>
 * </ol>
 * 
 * <h3>使用场景：</h3>
 * <ul>
 *   <li>第一次请求：创建 INPROGRESS 记录 -> 执行函数 -> 更新为 COMPLETED 记录</li>
 *   <li>重复请求：查找记录 -> 如果 COMPLETED，直接返回 responseData</li>
 *   <li>并发请求：查找记录 -> 如果 INPROGRESS，等待并重试</li>
 * </ul>
 */
public class DataRecord {
    /**
     * 幂等性键
     * 
     * <p><strong>含义：</strong>唯一标识一个请求的键，相同的请求会生成相同的键。</p>
     * 
     * <p>生成方式：使用调用方传入的完整 idempotencyKey 对象计算哈希值，格式为
     * {@code functionName#hash}。</p>
     * 
     * <h3>使用时机：</h3>
     * <ul>
     *   <li><strong>创建记录时：</strong>在 {@link com.wzg.idempotency.persistence.BasePersistenceStore#saveInProgress} 中生成</li>
     *   <li><strong>查找记录时：</strong>在 {@link com.wzg.idempotency.persistence.BasePersistenceStore#getRecord} 中使用</li>
     *   <li><strong>更新记录时：</strong>在 {@link com.wzg.idempotency.persistence.BasePersistenceStore#saveSuccess} 中使用</li>
     *   <li><strong>删除记录时：</strong>在 {@link com.wzg.idempotency.persistence.BasePersistenceStore#deleteRecord} 中使用</li>
     * </ul>
     * 
     * <h3>示例：</h3>
     * <pre>{@code
     * // idempotencyKey: {"orderId": "12345", "amount": 100.0}
     * // 生成的键: "processOrder#827ccb0eea8a706c4c34a16891f84e7b"
     * }</pre>
     */
    private final String idempotencyKey;
    
    /**
     * 记录状态
     * 
     * <p><strong>含义：</strong>记录当前的状态，用于判断如何处理请求。</p>
     * 
     * <h3>状态值：</h3>
     * <ul>
     *   <li><strong>INPROGRESS（执行中）：</strong>
     *       <ul>
     *         <li>请求正在执行中</li>
     *         <li>创建时机：调用 {@link com.wzg.idempotency.persistence.BasePersistenceStore#saveInProgress} 时</li>
     *         <li>处理方式：后续相同请求会抛出 {@link com.wzg.idempotency.exception.IdempotencyAlreadyInProgressException}，触发重试</li>
     *       </ul>
     *   </li>
     *   <li><strong>COMPLETED（已完成）：</strong>
     *       <ul>
     *         <li>请求已成功执行并保存结果</li>
     *         <li>创建时机：调用 {@link com.wzg.idempotency.persistence.BasePersistenceStore#saveSuccess} 时</li>
     *         <li>处理方式：后续相同请求直接返回 {@link #responseData}，不执行函数</li>
     *       </ul>
     *   </li>
     *   <li><strong>EXPIRED（已过期）：</strong>
     *       <ul>
     *         <li>记录已过期（通过 {@link #expiryTimestamp} 判断）</li>
     *         <li>判断时机：调用 {@link #getStatus()} 或 {@link #isExpired(Instant)} 时</li>
     *         <li>处理方式：记录会被清理，相同请求可以重新执行</li>
     *       </ul>
     *   </li>
     * </ul>
     * 
     * <h3>状态转换：</h3>
     * <pre>{@code
     * 第一次请求: 无记录 -> INPROGRESS -> COMPLETED
     * 重复请求:  查找记录 -> COMPLETED (返回缓存结果)
     * 并发请求:  查找记录 -> INPROGRESS (等待并重试)
     * 过期记录:  COMPLETED -> EXPIRED (自动过期)
     * }</pre>
     * 
     * <h3>使用时机：</h3>
     * <ul>
     *   <li><strong>判断记录状态：</strong>在 {@link com.wzg.idempotency.core.IdempotencyHandler#handleForStatus} 中使用</li>
     *   <li><strong>决定处理方式：</strong>根据状态决定是返回缓存结果还是等待执行完成</li>
     * </ul>
     */
    private final String status;
    
    /**
     * 过期时间戳（秒）
     * 
     * <p><strong>含义：</strong>记录过期的时间点（Unix 时间戳，单位：秒）。超过此时间后，记录被视为过期。</p>
     * 
     * <h3>计算方式：</h3>
     * <ul>
     *   <li>使用配置的 {@link com.wzg.idempotency.config.IdempotencyConfig#expirationInSeconds}（过期时间，单位：秒）</li>
     *   <li>计算公式：expiryTimestamp = now + expirationInSeconds</li>
     *   <li>例如：当前时间 2024-01-01 12:00:00，过期时间 3600 秒，则过期时间戳为 2024-01-01 13:00:00</li>
     * </ul>
     * 
     * <h3>使用时机：</h3>
     * <ul>
     *   <li><strong>创建记录时：</strong>在 {@link com.wzg.idempotency.persistence.BasePersistenceStore#saveInProgress} 和 {@link com.wzg.idempotency.persistence.BasePersistenceStore#saveSuccess} 中设置</li>
     *   <li><strong>判断是否过期：</strong>在 {@link #isExpired(Instant)} 中使用</li>
     *   <li><strong>获取状态时：</strong>在 {@link #getStatus()} 中使用，如果已过期返回 EXPIRED</li>
     *   <li><strong>设置 TTL：</strong>在 Redis 等持久化存储中，使用此值设置键的过期时间</li>
     * </ul>
     * 
     * <h3>过期处理：</h3>
     * <ul>
     *   <li>如果记录已过期（{@link #isExpired(Instant)} 返回 true），状态会被视为 EXPIRED</li>
     *   <li>过期的记录会被清理，相同请求可以重新执行</li>
     *   <li>这样可以避免记录永久占用存储空间</li>
     * </ul>
     * 
     * <h3>注意事项：</h3>
     * <ul>
     *   <li>过期时间设置得太短：可能导致记录很快过期，重复请求会重新执行</li>
     *   <li>过期时间设置得太长：会占用存储空间，且可能阻止合法的重复请求</li>
     *   <li>建议根据业务场景设置合理的过期时间（如 1 小时、24 小时等）</li>
     * </ul>
     */
    private final long expiryTimestamp;
    
    /**
     * 响应数据（JSON 字符串）
     * 
     * <p><strong>含义：</strong>函数执行成功后的返回值，序列化为 JSON 字符串格式存储。</p>
     * 
     * <h3>存储内容：</h3>
     * <ul>
     *   <li><strong>String 类型：</strong>直接存储字符串值</li>
     *   <li><strong>其他类型：</strong>使用 Jackson 序列化为 JSON 字符串</li>
     *   <li>例如：OrderResponse 对象会被序列化为 {"orderId":"123","status":"success"} 格式</li>
     * </ul>
     * 
     * <h3>使用时机：</h3>
     * <ul>
     *   <li><strong>保存时：</strong>
     *       <ul>
     *         <li>在 {@link com.wzg.idempotency.persistence.BasePersistenceStore#saveSuccess} 中设置</li>
     *         <li>函数执行成功后，将返回值序列化为 JSON 字符串并存储</li>
     *         <li>INPROGRESS 状态时，此字段为 null（因为还没有执行结果）</li>
     *       </ul>
     *   </li>
     *   <li><strong>读取时：</strong>
     *       <ul>
     *         <li>在 {@link com.wzg.idempotency.core.IdempotencyHandler#handleForStatus} 中使用</li>
     *         <li>如果记录状态为 COMPLETED，从 responseData 中反序列化并返回</li>
     *         <li>这样可以避免重复执行函数，直接返回缓存的结果</li>
     *       </ul>
     *   </li>
     * </ul>
     * 
     * <h3>反序列化：</h3>
     * <ul>
     *   <li>如果返回类型是 String：直接使用 responseData（已经是字符串）</li>
     *   <li>如果返回类型是其他类型：使用 Jackson 从 JSON 反序列化为目标类型</li>
     *   <li>支持泛型类型：使用 {@link com.fasterxml.jackson.core.type.TypeReference} 处理泛型</li>
     * </ul>
     * 
     * <h3>示例：</h3>
     * <pre>{@code
     * // 函数返回类型：OrderResponse
     * // 执行结果：OrderResponse{orderId="123", status="success"}
     * // 序列化后存储：{"orderId":"123","status":"success"}
     * // 重复请求时：从存储中读取并反序列化为 OrderResponse 对象返回
     * }</pre>
     * 
     * <h3>注意事项：</h3>
     * <ul>
     *   <li>INPROGRESS 状态时，responseData 为 null（因为还没有执行结果）</li>
     *   <li>COMPLETED 状态时，responseData 包含执行结果</li>
     *   <li>如果反序列化失败，会抛出 {@link com.wzg.idempotency.exception.IdempotencyPersistenceLayerException}</li>
     * </ul>
     */
    private final String responseData;
    
    /**
     * 载荷哈希值。
     *
     * <p>当前无表达式版本不再执行独立 payload validation，该字段作为记录结构兼容字段保留，
     * 核心逻辑写入空字符串。</p>
     */
    private final String payloadHash;
    
    /**
     * INPROGRESS 状态的过期时间戳（毫秒）
     * 
     * <p><strong>含义：</strong>当记录状态为 INPROGRESS 时，此字段表示执行中的过期时间点（Unix 时间戳，单位：毫秒）。
     * 超过此时间后，INPROGRESS 状态应该被视为过期。</p>
     * 
     * <h3>计算方式：</h3>
     * <ul>
     *   <li>如果提供了剩余执行时间（{@link com.wzg.idempotency.core.ExecutionContext#getRemainingTimeInMillis}）：
     *       <ul>
     *         <li>计算公式：inProgressExpiryTimestamp = now + remainingTimeInMs</li>
     *         <li>例如：当前时间 2024-01-01 12:00:00，剩余时间 5000 毫秒，则过期时间戳为 2024-01-01 12:00:05</li>
     *       </ul>
     *   </li>
     *   <li>如果没有提供剩余执行时间：
     *       <ul>
     *         <li>返回 {@link OptionalLong#empty()}（空值）</li>
     *       </ul>
     *   </li>
     * </ul>
     * 
     * <h3>使用时机：</h3>
     * <ul>
     *   <li><strong>创建 INPROGRESS 记录时：</strong>
     *       <ul>
     *         <li>在 {@link com.wzg.idempotency.persistence.BasePersistenceStore#saveInProgress} 中设置</li>
     *         <li>传入剩余执行时间（如 Lambda 函数的剩余执行时间）</li>
     *         <li>如果函数执行时间超过此时间，INPROGRESS 状态会自动过期</li>
     *       </ul>
     *   </li>
     *   <li><strong>验证 INPROGRESS 状态时：</strong>
     *       <ul>
     *         <li>在 {@link com.wzg.idempotency.core.IdempotencyHandler#handleForStatus} 中使用</li>
     *         <li>检查 INPROGRESS 状态是否已过期</li>
     *         <li>如果已过期，抛出 {@link com.wzg.idempotency.exception.IdempotencyInconsistentStateException}</li>
     *       </ul>
     *   </li>
     * </ul>
     * 
     * <h3>为什么需要这个字段？</h3>
     * <ul>
     *   <li><strong>防止死锁：</strong>如果函数执行失败或超时，INPROGRESS 状态不会永远存在</li>
     *   <li><strong>自动清理：</strong>超过执行时间后，INPROGRESS 状态会被视为过期，可以重新执行</li>
     *   <li><strong>并发安全：</strong>避免因为函数执行失败导致记录永远处于 INPROGRESS 状态</li>
     * </ul>
     * 
     * <h3>与 expiryTimestamp 的区别：</h3>
     * <ul>
     *   <li><strong>expiryTimestamp：</strong>记录的整体过期时间（秒），用于清理整个记录</li>
     *   <li><strong>inProgressExpiryTimestamp：</strong>INPROGRESS 状态的过期时间（毫秒），用于判断执行是否超时</li>
     *   <li>inProgressExpiryTimestamp 通常比 expiryTimestamp 短得多（如 5 秒 vs 1 小时）</li>
     * </ul>
     * 
     * <h3>示例：</h3>
     * <pre>{@code
     * // Lambda 函数剩余执行时间：5000 毫秒
     * // 当前时间：2024-01-01 12:00:00.000
     * // inProgressExpiryTimestamp = 2024-01-01 12:00:05.000
     * 
     * // 如果函数在 12:00:05 之前完成，状态会更新为 COMPLETED
     * // 如果函数在 12:00:05 之后仍未完成，INPROGRESS 状态会被视为过期
     * }</pre>
     * 
     * <h3>注意事项：</h3>
     * <ul>
     *   <li>只有 INPROGRESS 状态的记录才会有此字段</li>
     *   <li>COMPLETED 状态的记录，此字段为空（OptionalLong.empty()）</li>
     *   <li>如果没有提供剩余执行时间，此字段为空，不会进行过期检查</li>
     * </ul>
     */
    private final OptionalLong inProgressExpiryTimestamp;

    @JsonCreator
    public DataRecord(
            @JsonProperty("idempotencyKey") String idempotencyKey,
            @JsonProperty("status") String status,
            @JsonProperty("expiryTimestamp") long expiryTimestamp,
            @JsonProperty("responseData") String responseData,
            @JsonProperty("payloadHash") String payloadHash,
            @JsonProperty("inProgressExpiryTimestamp") Long inProgressExpiryTimestamp) {
        this.idempotencyKey = idempotencyKey;
        this.status = status;
        this.expiryTimestamp = expiryTimestamp;
        this.responseData = responseData;
        this.payloadHash = payloadHash;
        this.inProgressExpiryTimestamp = inProgressExpiryTimestamp != null 
                ? OptionalLong.of(inProgressExpiryTimestamp) 
                : OptionalLong.empty();
    }

    public DataRecord(String idempotencyKey, Status status, long expiryTimestamp, String responseData,
            String payloadHash) {
        this.idempotencyKey = idempotencyKey;
        this.status = status.toString();
        this.expiryTimestamp = expiryTimestamp;
        this.responseData = responseData;
        this.payloadHash = payloadHash;
        this.inProgressExpiryTimestamp = OptionalLong.empty();
    }

    public DataRecord(String idempotencyKey, Status status, long expiryTimestamp, String responseData,
            String payloadHash, OptionalLong inProgressExpiryTimestamp) {
        this.idempotencyKey = idempotencyKey;
        this.status = status.toString();
        this.expiryTimestamp = expiryTimestamp;
        this.responseData = responseData;
        this.payloadHash = payloadHash;
        this.inProgressExpiryTimestamp = inProgressExpiryTimestamp != null 
                ? inProgressExpiryTimestamp 
                : OptionalLong.empty();
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public boolean isExpired(Instant now) {
        return expiryTimestamp != 0 && now.isAfter(Instant.ofEpochSecond(expiryTimestamp));
    }

    public Status getStatus() {
        Instant now = Instant.now();
        if (isExpired(now)) {
            return Status.EXPIRED;
        } else {
            return Status.valueOf(status);
        }
    }

    public long getExpiryTimestamp() {
        return expiryTimestamp;
    }

    @JsonProperty("inProgressExpiryTimestamp")
    public Long getInProgressExpiryTimestampAsLong() {
        return inProgressExpiryTimestamp.isPresent() ? inProgressExpiryTimestamp.getAsLong() : null;
    }

    @JsonIgnore
    public OptionalLong getInProgressExpiryTimestamp() {
        return inProgressExpiryTimestamp;
    }

    public String getResponseData() {
        return responseData;
    }

    public String getPayloadHash() {
        return payloadHash;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        DataRecord record = (DataRecord) o;
        return expiryTimestamp == record.expiryTimestamp
                && idempotencyKey.equals(record.idempotencyKey)
                && status.equals(record.status)
                && Objects.equals(responseData, record.responseData)
                && Objects.equals(payloadHash, record.payloadHash);
    }

    @Override
    public int hashCode() {
        return Objects.hash(idempotencyKey, status, expiryTimestamp, responseData, payloadHash);
    }

    @Override
    public String toString() {
        return "DataRecord{" +
                "idempotencyKey='" + idempotencyKey + '\'' +
                ", status='" + status + '\'' +
                ", expiryTimestamp=" + expiryTimestamp +
                ", payloadHash='" + payloadHash + '\'' +
                '}';
    }

    public enum Status {
        INPROGRESS("INPROGRESS"), COMPLETED("COMPLETED"), EXPIRED("EXPIRED");

        private final String status;

        Status(String status) {
            this.status = status;
        }

        public String toString() {
            return status;
        }
    }
}
