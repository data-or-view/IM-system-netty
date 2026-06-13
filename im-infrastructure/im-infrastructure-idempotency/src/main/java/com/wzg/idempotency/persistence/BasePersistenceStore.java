package com.wzg.idempotency.persistence;

import com.wzg.idempotency.config.IdempotencyConfig;
import com.wzg.idempotency.core.ExecutionContext;
import com.wzg.idempotency.core.JsonConfig;
import com.wzg.idempotency.core.LRUCache;
import com.wzg.idempotency.exception.IdempotencyItemAlreadyExistsException;
import com.wzg.idempotency.exception.IdempotencyItemNotFoundException;
import com.wzg.idempotency.exception.IdempotencyKeyException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Base persistence layer implementation.
 * Extends this class to use your own implementation (Redis, JDBC, etc.)
 */
public abstract class BasePersistenceStore implements PersistenceStore {

    private static final Logger LOG = LoggerFactory.getLogger(BasePersistenceStore.class);
    
    /**
     * 函数名称
     * 
     * <p><strong>含义：</strong>当前处理的函数名称，用于生成唯一的幂等性键前缀。</p>
     * 
     * <h3>生成方式：</h3>
     * <ul>
     *   <li>从 {@link com.wzg.idempotency.core.ExecutionContext#getFunctionName()} 获取函数名</li>
     *   <li>如果提供了 functionName 参数，会追加到函数名后面（格式：functionName.functionName）</li>
     *   <li>如果没有 ExecutionContext，使用默认值 "defaultService"</li>
     * </ul>
     * 
     * <h3>使用时机：</h3>
     * <ul>
     *   <li>在 {@link #getHashedIdempotencyKey(JsonNode)} 方法中使用，作为幂等性键的前缀</li>
     *   <li>格式：functionName#hash（如 "processOrder#a1b2c3d4..."）</li>
     * </ul>
     * 
     * <h3>为什么需要函数名？</h3>
     * <ul>
     *   <li><strong>命名空间隔离：</strong>不同函数可以使用相同的 payload，但生成不同的幂等性键</li>
     *   <li><strong>避免冲突：</strong>防止不同函数的幂等性键冲突</li>
     *   <li><strong>便于管理：</strong>可以清楚地知道每个键属于哪个函数</li>
     * </ul>
     * 
     * <h3>示例：</h3>
     * <pre>{@code
     * // 函数1：processOrder
     * // payload: {"orderId": "123"}
     * // 生成的键: "processOrder#a1b2c3d4..."
     * 
     * // 函数2：processPayment
     * // payload: {"orderId": "123"}  // 相同的 payload
     * // 生成的键: "processPayment#a1b2c3d4..."  // 不同的键（因为函数名不同）
     * }</pre>
     */
    private String functionName = "";
    
    /**
     * 是否已配置
     * 
     * <p><strong>含义：</strong>标记持久化存储是否已经完成配置初始化。</p>
     * 
     * <h3>功能说明：</h3>
     * <ul>
     *   <li><strong>false（未配置，默认）：</strong>需要调用 {@link #configure(IdempotencyConfig, String)} 进行配置</li>
     *   <li><strong>true（已配置）：</strong>已经完成配置，不需要重复配置</li>
     * </ul>
     * 
     * <h3>使用时机：</h3>
     * <ul>
     *   <li>在 {@link #configure(IdempotencyConfig, String)} 方法中检查，如果已配置，直接返回，避免重复配置</li>
     *   <li>配置完成后，设置为 true</li>
     * </ul>
     * 
     * <h3>为什么需要这个标记？</h3>
     * <ul>
     *   <li><strong>防止重复配置：</strong>避免多次调用 configure 方法导致配置被覆盖</li>
     *   <li><strong>性能优化：</strong>如果已经配置过，直接返回，不需要重新计算</li>
     *   <li><strong>线程安全：</strong>虽然这个类本身不是线程安全的，但这个标记可以防止意外的重复配置</li>
     * </ul>
     * 
     * <h3>注意事项：</h3>
     * <ul>
     *   <li>一旦设置为 true，后续的 configure 调用会被忽略</li>
     *   <li>如果需要重新配置，需要创建新的 BasePersistenceStore 实例</li>
     * </ul>
     */
    private boolean configured = false;
    
    /**
     * 幂等性记录的过期时间（秒）
     * 
     * <p><strong>含义：</strong>幂等性记录在持久化存储中的过期时间，超过此时间后记录会被清理或视为过期。</p>
     * 
     * <h3>默认值：</h3>
     * <ul>
     *   <li>默认值为 3600 秒（1 小时）</li>
     *   <li>可以通过 {@link com.wzg.idempotency.config.IdempotencyConfig#expirationInSeconds} 配置</li>
     * </ul>
     * 
     * <h3>使用时机：</h3>
     * <ul>
     *   <li>在 {@link #getExpiryEpochSecond(Instant)} 方法中使用，计算记录的过期时间戳</li>
     *   <li>在创建 {@link DataRecord} 时使用，设置记录的过期时间</li>
     *   <li>在 Redis 等持久化存储中，使用此值设置键的 TTL（Time To Live）</li>
     * </ul>
     * 
     * <h3>过期处理：</h3>
     * <ul>
     *   <li>记录过期后，状态会被视为 {@link DataRecord.Status#EXPIRED}</li>
     *   <li>过期的记录会被清理，相同请求可以重新执行</li>
     *   <li>这样可以避免记录永久占用存储空间</li>
     * </ul>
     * 
     * <h3>设置建议：</h3>
     * <ul>
     *   <li><strong>设置得太短：</strong>记录很快过期，可能导致重复处理（如 60 秒）</li>
     *   <li><strong>设置得太长：</strong>占用存储空间，且可能阻止合法的重复请求（如 30 天）</li>
     *   <li><strong>推荐值：</strong>根据业务场景设置，一般建议 1-24 小时</li>
     * </ul>
     * 
     * <h3>示例：</h3>
     * <pre>{@code
     * // 配置过期时间为 2 小时
     * IdempotencyConfig.builder()
     *     .withExpiration(Duration.ofHours(2))
     *     .build();
     * // expirationInSeconds = 7200 秒
     * 
     * // 创建记录时：
     * // 当前时间：2024-01-01 12:00:00
     * // 过期时间：2024-01-01 14:00:00（当前时间 + 7200 秒）
     * }</pre>
     * 
     * <h3>注意事项：</h3>
     * <ul>
     *   <li>过期时间应该大于函数的最大执行时间</li>
     *   <li>如果函数执行时间很长，需要相应增加过期时间</li>
     *   <li>过期时间设置后，不要轻易改变，否则可能导致记录提前过期</li>
     * </ul>
     */
    private long expirationInSeconds = 60 * 60L; // 1 hour default
    
    /**
     * 是否启用本地缓存
     * 
     * <p><strong>含义：</strong>控制是否在内存中使用 LRU 缓存来缓存幂等性记录，以提高访问速度。</p>
     * 
     * <h3>功能说明：</h3>
     * <ul>
     *   <li><strong>true（启用）：</strong>
     *       <ul>
     *         <li>会在内存中维护一个 LRU（Least Recently Used）缓存</li>
     *         <li>缓存大小由 {@link #cache} 的容量控制（通过 localCacheMaxItems 配置）</li>
     *         <li>访问记录时，先检查缓存，如果命中则直接返回，避免访问持久化存储</li>
     *         <li>可以提高性能，但会占用内存</li>
     *       </ul>
     *   </li>
     *   <li><strong>false（禁用，默认）：</strong>
     *       <ul>
     *         <li>不启用本地缓存，每次都访问持久化存储（如 Redis）</li>
     *         <li>内存占用少，但访问速度较慢</li>
     *       </ul>
     *   </li>
     * </ul>
     * 
     * <h3>使用时机：</h3>
     * <ul>
     *   <li>在 {@link #retrieveFromCache(String, Instant)} 方法中使用，如果为 false，直接返回 null</li>
     *   <li>在 {@link #saveToCache(DataRecord)} 方法中使用，如果为 false，不保存到缓存</li>
     *   <li>在 {@link #deleteFromCache(String)} 方法中使用，如果为 false，不删除缓存</li>
     * </ul>
     * 
     * <h3>缓存策略：</h3>
     * <ul>
     *   <li>使用 LRU（最近最少使用）算法，自动淘汰最久未使用的记录</li>
     *   <li>只缓存 {@link DataRecord.Status#COMPLETED} 状态的记录</li>
     *   <li>不缓存 {@link DataRecord.Status#INPROGRESS} 状态的记录（因为可能很快会更新）</li>
     *   <li>缓存中的记录过期后会自动清理</li>
     * </ul>
     * 
     * <h3>适用场景：</h3>
     * <ul>
     *   <li><strong>高并发场景：</strong>大量重复请求，缓存可以显著提高性能</li>
     *   <li><strong>低延迟要求：</strong>需要快速响应，缓存可以减少网络延迟</li>
     *   <li><strong>内存充足：</strong>有足够的内存来维护缓存</li>
     * </ul>
     * 
     * <h3>示例：</h3>
     * <pre>{@code
     * // 启用本地缓存，最大条目数为 512
     * IdempotencyConfig.builder()
     *     .withUseLocalCache(true)
     *     .withLocalCacheMaxItems(512)
     *     .build();
     * 
     * // 第一次请求：从 Redis 读取记录，保存到本地缓存
     * // 第二次请求：直接从本地缓存读取，不需要访问 Redis（性能提升）
     * }</pre>
     * 
     * <h3>注意事项：</h3>
     * <ul>
     *   <li>本地缓存只在单个实例中有效，多实例部署时每个实例都有自己的缓存</li>
     *   <li>缓存中的数据可能与持久化存储不一致（如果记录被其他实例更新）</li>
     *   <li>建议在单实例或对一致性要求不高的场景中使用</li>
     * </ul>
     */
    private boolean useLocalCache = false;
    
    /**
     * 本地 LRU 缓存实例
     * 
     * <p><strong>含义：</strong>用于缓存幂等性记录的 LRU（Least Recently Used）缓存。</p>
     * 
     * <h3>初始化：</h3>
     * <ul>
     *   <li>如果 {@link #useLocalCache} 为 true，在 {@link #configure(IdempotencyConfig, String)} 中创建</li>
     *   <li>缓存容量由 {@link com.wzg.idempotency.config.IdempotencyConfig#localCacheMaxItems} 配置（默认 256）</li>
     *   <li>如果 useLocalCache 为 false，此字段为 null</li>
     * </ul>
     * 
     * <h3>使用时机：</h3>
     * <ul>
     *   <li>在 {@link #retrieveFromCache(String, Instant)} 中使用，从缓存中获取记录</li>
     *   <li>在 {@link #saveToCache(DataRecord)} 中使用，保存记录到缓存</li>
     *   <li>在 {@link #deleteFromCache(String)} 中使用，从缓存中删除记录</li>
     * </ul>
     * 
     * <h3>缓存策略：</h3>
     * <ul>
     *   <li>键：幂等性键（idempotencyKey）</li>
     *   <li>值：{@link DataRecord} 对象</li>
     *   <li>淘汰策略：LRU（最近最少使用），自动淘汰最久未使用的记录</li>
     *   <li>只缓存 COMPLETED 状态的记录，不缓存 INPROGRESS 状态的记录</li>
     * </ul>
     * 
     * <h3>注意事项：</h3>
     * <ul>
     *   <li>缓存中的数据可能与持久化存储不一致</li>
     *   <li>缓存中的记录过期后会自动清理</li>
     *   <li>多实例部署时，每个实例都有自己的缓存</li>
     * </ul>
     */
    private LRUCache<String, DataRecord> cache;
    
    /**
     * 当无法生成幂等性键时是否抛出异常
     * 
     * <p><strong>含义：</strong>控制当无法从 payload 中提取或生成幂等性键时的行为。</p>
     * 
     * <h3>功能说明：</h3>
     * <ul>
     *   <li><strong>true（严格模式）：</strong>
     *       <ul>
     *         <li>如果无法生成键，抛出 {@link com.wzg.idempotency.exception.IdempotencyKeyException}</li>
     *         <li>适合生产环境，可以及时发现配置错误</li>
     *       </ul>
     *   </li>
     *   <li><strong>false（宽松模式，默认）：</strong>
     *       <ul>
     *         <li>如果无法生成键，记录警告日志，返回空 Optional</li>
     *         <li>幂等性功能会被跳过，函数正常执行</li>
     *         <li>适合开发环境，可以容忍配置错误</li>
     *       </ul>
     *   </li>
     * </ul>
     * 
     * <h3>无法生成键的情况：</h3>
     * <ul>
     *   <li>payload 为 null 或空</li>
     *   <li>payload 中没有可用于生成哈希的内容</li>
     * </ul>
     * 
     * <h3>使用时机：</h3>
     * <ul>
     *   <li>在 {@link #getHashedIdempotencyKey(JsonNode)} 方法中使用</li>
     *   <li>如果无法生成键且 throwOnNoIdempotencyKey 为 true，抛出异常</li>
     *   <li>如果为 false，记录警告并返回空 Optional</li>
     * </ul>
     * 
     * <h3>示例：</h3>
     * <pre>{@code
     * // 严格模式
     * IdempotencyConfig.builder()
     *     .withThrowOnNoIdempotencyKey(true)
     *     .build();
     * // 如果无法生成键，抛出 IdempotencyKeyException
     * 
     * // 宽松模式（默认）
     * IdempotencyConfig.builder()
     *     .withThrowOnNoIdempotencyKey(false)  // 或省略
     *     .build();
     * // 如果无法生成键，记录警告，幂等性功能被跳过
     * }</pre>
     * 
     * <h3>注意事项：</h3>
     * <ul>
     *   <li>生产环境建议设置为 true，可以及时发现配置错误</li>
     *   <li>开发环境可以设置为 false，方便调试</li>
     *   <li>如果设置为 false，无法生成键时幂等性功能会被跳过，可能导致重复执行</li>
     * </ul>
     */
    private boolean throwOnNoIdempotencyKey = false;
    
    /**
     * 哈希算法名称
     * 
     * <p><strong>含义：</strong>用于生成幂等性键和载荷哈希值的哈希算法名称。</p>
     * 
     * <h3>支持的算法：</h3>
     * <ul>
     *   <li><strong>MD5（默认）：</strong>
     *       <ul>
     *         <li>速度快，但安全性较低（可能碰撞）</li>
     *         <li>适合对安全性要求不高的场景</li>
     *       </ul>
     *   </li>
     *   <li><strong>SHA-256：</strong>
     *       <ul>
     *         <li>安全性高，但速度稍慢</li>
     *         <li>适合对安全性要求高的场景</li>
     *       </ul>
     *   </li>
     *   <li><strong>其他算法：</strong>支持 Java MessageDigest 支持的所有算法</li>
     * </ul>
     * 
     * <h3>使用时机：</h3>
     * <ul>
     *   <li>在 {@link #generateHash(JsonNode)} 方法中使用</li>
     *   <li>在 {@link #getHashAlgorithm()} 方法中使用，创建 MessageDigest 实例</li>
     *   <li>用于生成幂等性键和载荷哈希值</li>
     * </ul>
     * 
     * <h3>哈希流程：</h3>
     * <pre>{@code
     * // 1. 获取哈希算法实例
     * MessageDigest hashAlgorithm = MessageDigest.getInstance(hashFunctionName);
     * 
     * // 2. 计算哈希值
     * byte[] digest = hashAlgorithm.digest(data.getBytes());
     * 
     * // 3. 转换为十六进制字符串
     * String hash = String.format("%032x", new BigInteger(1, digest));
     * }</pre>
     * 
     * <h3>配置方式：</h3>
     * <ul>
     *   <li>通过 {@link com.wzg.idempotency.config.IdempotencyConfig.Builder#withHashFunction(String)} 配置</li>
     *   <li>默认值为 "MD5"</li>
     * </ul>
     * 
     * <h3>示例：</h3>
     * <pre>{@code
     * // 使用 MD5（默认）
     * IdempotencyConfig.builder()
     *     .withHashFunction("MD5")  // 或省略
     *     .build();
     * 
     * // 使用 SHA-256
     * IdempotencyConfig.builder()
     *     .withHashFunction("SHA-256")
     *     .build();
     * }</pre>
     * 
     * <h3>注意事项：</h3>
     * <ul>
     *   <li>一旦设置了哈希算法，不要轻易改变，否则会导致键不一致</li>
     *   <li>如果指定的算法不存在，会回退到 MD5</li>
     *   <li>生产环境建议使用 SHA-256，提高安全性</li>
     * </ul>
     */
    private String hashFunctionName;

    /**
     * Initialize the base persistence layer from the configuration settings
     *
     * @param config       Idempotency configuration settings
     * @param functionName The name of the function being decorated
     */
    public void configure(IdempotencyConfig config, String functionName) {
        ExecutionContext executionContext = config.getExecutionContext();
        this.functionName = executionContext != null ? executionContext.getFunctionName() : "defaultService";
        if (functionName != null && !functionName.isEmpty()) {
            this.functionName += "." + functionName;
        }

        if (configured) {
            return;
        }

        // 配置：throwOnNoIdempotencyKey - 无法生成键时是否抛出异常
        // true：严格模式，配置错误会立即报错；false：宽松模式，静默处理
        throwOnNoIdempotencyKey = config.throwOnNoIdempotencyKey();

        // 配置：useLocalCache - 是否启用本地缓存
        // true：启用本地LRU缓存，提高性能但占用内存；false：禁用，每次都访问持久化存储
        useLocalCache = config.useLocalCache();
        if (useLocalCache) {
            // 配置：localCacheMaxItems - 本地缓存的最大条目数
            // 控制缓存大小，防止内存溢出
            cache = new LRUCache<>(config.getLocalCacheMaxItems());
        }
        
        // 配置：expirationInSeconds - 幂等性记录的过期时间（秒）
        // 超过这个时间后，记录会被清理或视为过期，允许重新处理
        expirationInSeconds = config.getExpirationInSeconds();
        
        // 配置：hashFunction - 用于生成哈希值的哈希算法名称
        // 将payload转换为固定长度的哈希值，用于生成幂等性键
        hashFunctionName = config.getHashFunction();
        configured = true;
    }

    public void saveSuccess(JsonNode data, Object result, Instant now) {
        ObjectWriter writer = JsonConfig.get().getObjectMapper().writer();
        try {
            String responseJson;
            if (result instanceof String) {
                responseJson = (String) result;
            } else {
                responseJson = writer.writeValueAsString(result);
            }
            Optional<String> hashedIdempotencyKey = getHashedIdempotencyKey(data);
            if (!hashedIdempotencyKey.isPresent()) {
                return;
            }
            DataRecord dataRecord = new DataRecord(
                    hashedIdempotencyKey.get(),
                    DataRecord.Status.COMPLETED,
                    getExpiryEpochSecond(now),
                    responseJson,
                    "");
            LOG.debug("Function successfully executed. Saving record to persistence store with idempotency key: {}",
                    dataRecord.getIdempotencyKey());
            updateRecord(dataRecord);
            saveToCache(dataRecord);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Error while serializing the response", e);
        }
    }

    public void saveInProgress(JsonNode data, Instant now, OptionalInt remainingTimeInMs)
            throws IdempotencyItemAlreadyExistsException {
        Optional<String> hashedIdempotencyKey = getHashedIdempotencyKey(data);
        if (!hashedIdempotencyKey.isPresent()) {
            return;
        }

        String idempotencyKey = hashedIdempotencyKey.get();
        if (retrieveFromCache(idempotencyKey, now) != null) {
            throw new IdempotencyItemAlreadyExistsException();
        }

        OptionalLong inProgressExpirationMsTimestamp = OptionalLong.empty();
        if (remainingTimeInMs.isPresent()) {
            inProgressExpirationMsTimestamp = OptionalLong
                    .of(now.plus(remainingTimeInMs.getAsInt(), ChronoUnit.MILLIS).toEpochMilli());
        }

        // 这里把这个幂等性key保存到实体中
        DataRecord dataRecord = new DataRecord(
                idempotencyKey,
                DataRecord.Status.INPROGRESS,
                getExpiryEpochSecond(now),
                null,
                "",
                inProgressExpirationMsTimestamp);
        LOG.debug("saving in progress record for idempotency key: {}", dataRecord.getIdempotencyKey());

        try {
            putRecord(dataRecord, now);
        } catch (IdempotencyItemAlreadyExistsException iaee) {
            Optional<DataRecord> dr = iaee.getDataRecord();
            if (dr.isPresent()) {
                LOG.debug("Existing idempotency record found for key: {}", dr.get().getIdempotencyKey());
            }
            throw iaee;
        }
    }

    public void deleteRecord(JsonNode data, Throwable throwable) {
        Optional<String> hashedIdempotencyKey = getHashedIdempotencyKey(data);
        if (!hashedIdempotencyKey.isPresent()) {
            return;
        }

        String idemPotencyKey = hashedIdempotencyKey.get();
        LOG.debug("Function raised an exception {}. " +
                "Clearing in progress record in persistence store for idempotency key: {}",
                throwable.getClass(),
                idemPotencyKey);

        deleteRecord(idemPotencyKey);
        deleteFromCache(idemPotencyKey);
    }

    public DataRecord getRecord(JsonNode data, Instant now)
            throws IdempotencyItemNotFoundException {
        Optional<String> hashedIdempotencyKey = getHashedIdempotencyKey(data);
        if (!hashedIdempotencyKey.isPresent()) {
            return null;
        }

        String idemPotencyKey = hashedIdempotencyKey.get();
        DataRecord cachedRecord = retrieveFromCache(idemPotencyKey, now);
        if (cachedRecord != null) {
            LOG.debug("Idempotency record found in cache with idempotency key: {}", idemPotencyKey);
            return cachedRecord;
        }

        DataRecord dataRecord = getRecord(idemPotencyKey);
        saveToCache(dataRecord);
        return dataRecord;
    }

    private Optional<String> getHashedIdempotencyKey(JsonNode data) {
        JsonNode node = data;

        if (isMissingIdemPotencyKey(node)) {
            if (throwOnNoIdempotencyKey) {
                throw new IdempotencyKeyException("No data found to create a hashed idempotency key");
            } else {
                LOG.warn("No data found to create a hashed idempotency key.");
                return Optional.empty();
            }
        }

        String hash = generateHash(node);
        hash = functionName + "#" + hash;
        return Optional.of(hash);
    }

    private boolean isMissingIdemPotencyKey(JsonNode data) {
        if (data.isContainerNode()) {
            Stream<JsonNode> stream = StreamSupport.stream(
                    Spliterators.spliteratorUnknownSize(data.elements(), Spliterator.ORDERED),
                    false);
            return stream.allMatch(JsonNode::isNull);
        }
        return data.isNull();
    }

    String generateHash(JsonNode data) {
        Object node;
        if (data.isContainerNode()) {
            node = data.toString();
        } else if (data.isTextual()) {
            node = data.asText();
        } else if (data.isInt()) {
            node = data.asInt();
        } else if (data.isLong()) {
            node = data.asLong();
        } else if (data.isDouble()) {
            node = data.asDouble();
        } else if (data.isFloat()) {
            node = data.floatValue();
        } else if (data.isBigInteger()) {
            node = data.bigIntegerValue();
        } else if (data.isBigDecimal()) {
            node = data.decimalValue();
        } else if (data.isBoolean()) {
            node = data.asBoolean();
        } else {
            node = data;
        }

        MessageDigest hashAlgorithm = getHashAlgorithm();
        byte[] digest = hashAlgorithm.digest(node.toString().getBytes(StandardCharsets.UTF_8));
        return String.format("%032x", new BigInteger(1, digest));
    }

    /**
     * 获取哈希算法实例
     * 
     * 使用配置的 hashFunction（哈希算法名称）来创建MessageDigest实例
     * 如果指定的算法不存在，会回退到MD5
     * 
     * 哈希算法的作用：将payload转换为固定长度的哈希值，用于生成幂等性键
     * - MD5：速度快，但安全性较低（可能碰撞）
     * - SHA-256：安全性高，但速度稍慢
     * 
     * 注意：一旦设置了哈希算法，不要轻易改变，否则会导致键不一致
     */
    @SuppressWarnings("java:S4790")
    private MessageDigest getHashAlgorithm() {
        MessageDigest hashAlgorithm;
        try {
            // 使用配置的 hashFunction 创建哈希算法实例
            hashAlgorithm = MessageDigest.getInstance(hashFunctionName);
        } catch (NoSuchAlgorithmException e) {
            LOG.warn("Error instantiating {} hash function, trying with MD5", hashFunctionName);
            try {
                // 如果配置的算法不存在，回退到MD5
                hashAlgorithm = MessageDigest.getInstance("MD5");
            } catch (NoSuchAlgorithmException ex) {
                throw new RuntimeException("Unable to instantiate MD5 digest", ex);
            }
        }
        return hashAlgorithm;
    }

    /**
     * 计算记录的过期时间戳（秒）
     * 
     * 使用配置的 expirationInSeconds（过期时间）来计算记录的过期时间
     * 如果过期时间设置得太短，记录会很快过期，可能导致重复处理
     * 如果过期时间设置得太长，会占用存储空间，且可能阻止合法的重复请求
     */
    private long getExpiryEpochSecond(Instant now) {
        // 使用配置的 expirationInSeconds 计算过期时间
        return now.plus(expirationInSeconds, ChronoUnit.SECONDS).getEpochSecond();
    }

    /**
     * 保存记录到本地缓存
     * 
     * 使用配置的 useLocalCache 和 localCacheMaxItems
     * 如果启用了本地缓存，会将记录保存到LRU缓存中，提高后续访问速度
     */
    private void saveToCache(DataRecord dataRecord) {
        // 检查是否启用了本地缓存（useLocalCache配置）
        if (!useLocalCache) {
            return;
        }
        // INPROGRESS状态的记录不缓存，因为可能很快会更新
        if (dataRecord.getStatus().equals(DataRecord.Status.INPROGRESS)) {
            return;
        }
        // 保存到LRU缓存，缓存大小由 localCacheMaxItems 配置控制
        cache.put(dataRecord.getIdempotencyKey(), dataRecord);
    }

    private DataRecord retrieveFromCache(String idempotencyKey, Instant now) {
        if (!useLocalCache) {
            return null;
        }

        DataRecord dataRecord = cache.get(idempotencyKey);
        if (dataRecord != null) {
            if (!dataRecord.isExpired(now)) {
                return dataRecord;
            }
            LOG.debug("Removing expired local cache record for idempotency key: {}", idempotencyKey);
            deleteFromCache(idempotencyKey);
        }
        return null;
    }

    private void deleteFromCache(String idempotencyKey) {
        if (!useLocalCache) {
            return;
        }
        cache.remove(idempotencyKey);
    }

    /**
     * 源码中他好像是测试才需要，但是我们自己写的项目没使用这个
     * @param config
     * @param functionName
     * @param cache
     */
    void configure(IdempotencyConfig config, String functionName, LRUCache<String, DataRecord> cache) {
        this.configure(config, functionName);
        this.cache = cache;
    }

}
