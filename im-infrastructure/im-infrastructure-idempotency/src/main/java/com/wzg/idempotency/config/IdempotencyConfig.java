package com.wzg.idempotency.config;

import com.wzg.idempotency.core.ExecutionContext;
import com.wzg.idempotency.core.LRUCache;
import com.wzg.idempotency.persistence.DataRecord;

import java.time.Duration;
import java.util.function.BiFunction;

/**
 * Configuration of the idempotency feature. Use the Builder to create an instance.
 * 
 * 幂等性配置类，包含所有幂等性功能的配置参数
 */
public final class IdempotencyConfig {
    
    /**
     * 本地缓存的最大条目数
     * 
     * 作用：
     * - 控制本地LRU缓存中最多存储多少条幂等性记录
     * - 当缓存满时，会淘汰最久未使用的记录
     * 
     * 为什么需要：
     * - 本地缓存可以快速访问最近使用的记录，避免频繁访问Redis等远程存储
     * - 提高性能，减少网络延迟
     * 
     * 变化影响：
     * - 值太小：缓存命中率低，性能提升有限
     * - 值太大：占用内存过多，可能导致OOM
     * - 默认值：256（适合大多数场景）
     * 
     * 使用场景：
     * - 高并发场景：可以设置较大值（如1000-10000）
     * - 内存受限场景：可以设置较小值（如64-128）
     */
    private final int localCacheMaxItems;
    
    /**
     * 是否启用本地缓存
     * 
     * 作用：
     * - 控制是否在内存中缓存幂等性记录
     * - true：启用本地LRU缓存，提高访问速度
     * - false：禁用本地缓存，每次都访问持久化存储（Redis等）
     * 
     * 为什么需要：
     * - 本地缓存可以显著提高性能（减少网络IO）
     * - 但会增加内存使用，且不支持分布式场景（多实例间不共享）
     * 
     * 变化影响：
     * - true：性能好，但内存占用增加，多实例间可能不一致
     * - false：性能稍差，但内存占用少，多实例间一致性好
     * - 默认值：false（推荐用于分布式环境）
     * 
     * 使用场景：
     * - 单实例应用：可以启用（true）
     * - 分布式应用：建议禁用（false），使用Redis等共享存储
     */
    private final boolean useLocalCache;
    
    /**
     * 幂等性记录的过期时间（秒）
     * 
     * 作用：
     * - 控制幂等性记录在存储中保留多长时间
     * - 超过这个时间后，记录会被自动清理或视为过期
     * 
     * 为什么需要：
     * - 防止存储空间无限增长
     * - 过期后的记录可以重新处理（允许重复请求）
     * - 根据业务需求设置合理的过期时间
     * 
     * 变化影响：
     * - 值太小：记录很快过期，可能导致重复处理（失去幂等性保护）
     * - 值太大：占用存储空间，且可能阻止合法的重复请求
     * - 默认值：3600秒（1小时）
     * 
     * 使用场景：
     * - 支付场景：建议设置较长（如24小时），防止重复支付
     * - 查询场景：可以设置较短（如10分钟），允许重复查询
     * - 临时操作：可以设置很短（如5分钟）
     */
    private final long expirationInSeconds;
    
    /**
     * 当无法生成幂等性键时是否抛出异常
     * 
     * 作用：
     * - 控制当payload为空或无法提取键时的行为
     * - true：抛出异常，强制要求提供有效的payload
     * - false：静默处理，允许继续执行（但失去幂等性保护）
     * 
     * 为什么需要：
     * - 强制开发者正确使用幂等性功能
     * - 防止因为配置错误导致幂等性失效
     * - 在开发阶段更容易发现问题
     * 
     * 变化影响：
     * - true：严格模式，配置错误会立即报错（推荐用于生产环境）
     * - false：宽松模式，配置错误会静默忽略（可能导致幂等性失效）
     * - 默认值：false（宽松模式）
     * 
     * 使用场景：
     * - 生产环境：建议设置为true，确保幂等性正常工作
     * - 开发/测试环境：可以设置为false，方便调试
     */
    private final boolean throwOnNoIdempotencyKey;
    
    /**
     * 用于生成哈希值的哈希算法名称
     * 
     * 作用：
     * - 指定生成幂等性键时使用的哈希算法
     * - 将payload转换为固定长度的哈希值
     * 
     * 为什么需要：
     * - 将任意长度的payload转换为固定长度的键
     * - 确保相同payload生成相同键
     * - 不同payload生成不同键（理论上）
     * 
     * 变化影响：
     * - MD5：速度快，但安全性较低（可能碰撞）
     * - SHA-256：安全性高，但速度稍慢
     * - 默认值："MD5"（平衡性能和安全性）
     * 
     * 使用场景：
     * - 一般业务：MD5足够（性能好）
     * - 高安全性要求：使用SHA-256或SHA-512
     * 
     * 注意事项：
     * - 一旦设置，不要轻易改变（会导致键不一致）
     * - 如果改变，需要清理所有现有记录
     */
    private final String hashFunction;
    
    /**
     * 响应钩子函数
     * 
     * 作用：
     * - 在返回缓存结果前，对响应进行自定义处理
     * - 可以转换响应格式、添加额外信息、记录日志等
     * 
     * 为什么需要：
     * - 允许在返回缓存结果前进行自定义处理
     * - 可以添加时间戳、请求ID等动态信息
     * - 可以转换响应格式，满足不同需求
     * 
     * 变化影响：
     * - null：直接返回缓存的结果（默认行为）
     * - 设置函数：返回函数处理后的结果（可以自定义）
     * - 默认值：null（不处理）
     * 
     * 使用示例：
     * - 添加响应时间戳
     * - 转换响应格式
     * - 记录响应日志
     * 
     * 注意事项：
     * - 钩子函数应该是纯函数（不修改原始数据）
     * - 不要执行耗时操作（会影响响应时间）
     */
    private final BiFunction<Object, DataRecord, Object> responseHook;
    
    /**
     * 执行上下文（线程本地变量）
     * 
     * 作用：
     * - 存储当前请求的执行上下文信息
     * - 用于获取函数名、剩余执行时间等
     * 
     * 为什么需要：
     * - 提供请求执行的相关信息
     * - 用于设置INPROGRESS状态的过期时间
     * - 支持Lambda函数等特殊场景
     * 
     * 变化影响：
     * - 设置：可以获取执行上下文信息，支持超时控制
     * - 不设置：使用默认值，可能无法正确设置超时
     * 
     * 注意事项：
     * - 使用InheritableThreadLocal支持线程池场景
     * - 需要在请求开始时设置
     */
    private final InheritableThreadLocal<ExecutionContext> executionContext = new InheritableThreadLocal<>();

    private IdempotencyConfig(boolean throwOnNoIdempotencyKey, boolean useLocalCache, int localCacheMaxItems,
            long expirationInSeconds, String hashFunction, BiFunction<Object, DataRecord, Object> responseHook) {
        this.localCacheMaxItems = localCacheMaxItems;
        this.useLocalCache = useLocalCache;
        this.expirationInSeconds = expirationInSeconds;
        this.throwOnNoIdempotencyKey = throwOnNoIdempotencyKey;
        this.hashFunction = hashFunction;
        this.responseHook = responseHook;
    }

    public static Builder builder() {
        return new Builder();
    }

    public int getLocalCacheMaxItems() {
        return localCacheMaxItems;
    }

    public boolean useLocalCache() {
        return useLocalCache;
    }

    public long getExpirationInSeconds() {
        return expirationInSeconds;
    }

    public boolean throwOnNoIdempotencyKey() {
        return throwOnNoIdempotencyKey;
    }

    public String getHashFunction() {
        return hashFunction;
    }

    public ExecutionContext getExecutionContext() {
        return executionContext.get();
    }

    public void setExecutionContext(ExecutionContext executionContext) {
        this.executionContext.set(executionContext);
    }

    public BiFunction<Object, DataRecord, Object> getResponseHook() {
        return responseHook;
    }

    public static class Builder {
        private int localCacheMaxItems = 256;
        private boolean useLocalCache = false;
        private long expirationInSeconds = 60 * 60L; // 1 hour
        private boolean throwOnNoIdempotencyKey = false;
        private String hashFunction = "MD5";
        private BiFunction<Object, DataRecord, Object> responseHook;

        public IdempotencyConfig build() {
            return new IdempotencyConfig(
                    throwOnNoIdempotencyKey,
                    useLocalCache,
                    localCacheMaxItems,
                    expirationInSeconds,
                    hashFunction,
                    responseHook);
        }

        public Builder withLocalCacheMaxItems(int localCacheMaxItems) {
            this.localCacheMaxItems = localCacheMaxItems;
            return this;
        }

        public Builder withUseLocalCache(boolean useLocalCache) {
            this.useLocalCache = useLocalCache;
            return this;
        }

        public Builder withExpiration(Duration expiration) {
            this.expirationInSeconds = expiration.getSeconds();
            return this;
        }

        public Builder withThrowOnNoIdempotencyKey(boolean throwOnNoIdempotencyKey) {
            this.throwOnNoIdempotencyKey = throwOnNoIdempotencyKey;
            return this;
        }

        public Builder withThrowOnNoIdempotencyKey() {
            return withThrowOnNoIdempotencyKey(true);
        }

        public Builder withHashFunction(String hashFunction) {
            this.hashFunction = hashFunction;
            return this;
        }

        public Builder withResponseHook(BiFunction<Object, DataRecord, Object> responseHook) {
            this.responseHook = responseHook;
            return this;
        }
    }
}
