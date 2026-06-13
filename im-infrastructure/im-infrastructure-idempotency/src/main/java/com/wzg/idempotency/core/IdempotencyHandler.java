package com.wzg.idempotency.core;

import com.wzg.idempotency.config.IdempotencyConfig;
import com.wzg.idempotency.exception.IdempotencyAlreadyInProgressException;
import com.wzg.idempotency.exception.IdempotencyInconsistentStateException;
import com.wzg.idempotency.exception.IdempotencyItemAlreadyExistsException;
import com.wzg.idempotency.exception.IdempotencyItemNotFoundException;
import com.wzg.idempotency.exception.IdempotencyKeyException;
import com.wzg.idempotency.exception.IdempotencyPersistenceLayerException;
import com.wzg.idempotency.exception.IdempotencyValidationException;
import com.wzg.idempotency.persistence.BasePersistenceStore;
import com.wzg.idempotency.persistence.DataRecord;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.OptionalInt;
import java.util.function.BiFunction;

import static com.wzg.idempotency.persistence.DataRecord.Status.EXPIRED;
import static com.wzg.idempotency.persistence.DataRecord.Status.INPROGRESS;

/**
 * 幂等性处理器
 * 
 * 这是幂等性模块的核心类，负责处理幂等性逻辑：
 * 1. 检查是否已执行过相同的请求（通过幂等性键）
 * 2. 如果已执行过且记录未过期，直接返回缓存的结果
 * 3. 如果未执行过，执行函数并将结果存储到持久化层
 * 4. 处理并发场景下的重试逻辑
 * 
 * 工作流程：
 * handle() -> processIdempotency() -> saveInProgress() / handleForStatus() / getFunctionResponse()
 * 
 * 状态处理：
 * - 记录不存在：执行函数并保存结果
 * - 记录存在且状态为 COMPLETED：返回缓存结果
 * - 记录存在且状态为 INPROGRESS：等待并重试（并发场景）
 * - 记录存在但已过期：重新执行函数
 */
public class IdempotencyHandler {
    private static final Logger LOG = LoggerFactory.getLogger(IdempotencyHandler.class);
    /**
     * 最大重试次数
     * 在高并发场景下，如果记录正在执行中（INPROGRESS），会进行重试
     * 重试次数增加到5次，以应对高并发场景
     */
    private static final int MAX_RETRIES = 5;

    /**
     * 实际要执行的函数（lambda 表达式）
     * 实际要执行的幂等函数逻辑
     */
    private final IdempotentFunction<?> function;
    
    /**
     * 函数返回类型的类型引用
     * 用于反序列化从持久化存储中读取的缓存结果
     */
    private final TypeReference<?> returnTypeRef;
    
    /**
     * 请求的负载数据（payload）
     * 用于生成幂等性键，相同的 payload 会生成相同的键
     */
    private final JsonNode data;
    
    /**
     * 持久化存储接口
     * 用于存储和检索幂等性记录（如 RedisPersistenceStore、RedissonPersistenceStore）
     */
    private final BasePersistenceStore persistenceStore;
    
    /**
     * 执行上下文（可选）
     * 用于获取函数执行的相关信息，如剩余执行时间等
     */
    private final ExecutionContext executionContext;
    
    /**
     * 幂等性配置
     * 包含过期时间、本地缓存等配置信息
     */
    private final IdempotencyConfig config;

    /**
     * 构造函数（使用 Class 类型）
     * 
     * @param function 实际要执行的函数
     * @param returnType 函数返回类型（Class）
     * @param functionName 函数名，用于生成唯一的幂等性键
     * @param payload 请求负载数据
     * @param executionContext 执行上下文（可选）
     * @param persistenceStore 持久化存储
     * @param config 幂等性配置
     */
    public IdempotencyHandler(IdempotentFunction<?> function, Class<?> returnType, String functionName,
            JsonNode payload, ExecutionContext executionContext, BasePersistenceStore persistenceStore,
            IdempotencyConfig config) {
        // 将 Class 转换为 TypeReference，然后调用另一个构造函数
        this(function, JsonConfig.toTypeReference(returnType), functionName, payload, executionContext,
                persistenceStore, config);
    }

    /**
     * 构造函数（使用 TypeReference 类型）
     * 
     * 这是主要的构造函数，初始化所有字段并配置持久化存储
     * 
     * @param function 实际要执行的函数
     * @param returnTypeRef 函数返回类型的类型引用
     * @param functionName 函数名，用于生成唯一的幂等性键
     * @param payload 请求负载数据
     * @param executionContext 执行上下文（可选）
     * @param persistenceStore 持久化存储
     * @param config 幂等性配置
     */
    public IdempotencyHandler(IdempotentFunction<?> function, TypeReference<?> returnTypeRef, String functionName,
            JsonNode payload, ExecutionContext executionContext, BasePersistenceStore persistenceStore,
            IdempotencyConfig config) {
        this.function = function;
        this.returnTypeRef = returnTypeRef;
        this.data = payload;
        this.executionContext = executionContext;
        this.persistenceStore = persistenceStore;
        this.config = config;
        if (executionContext != null) {
            config.setExecutionContext(executionContext);
        }
        // 配置持久化存储，传入配置和函数名
        // 函数名会用于生成唯一的幂等性键前缀
        persistenceStore.configure(config, functionName);
    }

    /**
     * 处理幂等性逻辑的主入口方法
     * 
     * 这是幂等性处理的核心方法，包含重试机制来处理并发场景：
     * 1. 调用 processIdempotency() 处理幂等性逻辑
     * 2. 如果记录正在执行中（INPROGRESS），使用指数退避策略重试
     * 3. 如果状态不一致，也会进行重试
     * 
     * 重试策略：
     * - 指数退避：10ms, 20ms, 40ms, 80ms, 160ms
     * - 最大重试次数：5次
     * - 重试原因：记录正在执行中（并发场景）
     * 
     * 【重试的核心目的：等待第一个请求执行成功的结果】
     * 重试机制的本质是在等待第一个请求执行成功的结果：
     * 1. 第一个请求：成功保存 INPROGRESS 状态，开始执行函数
     * 2. 其他请求：发现记录状态为 INPROGRESS，抛出 IdempotencyAlreadyInProgressException
     * 3. 重试等待：等待一段时间后，重新调用 processIdempotency()
     * 4. 重新检查：再次获取记录，检查状态是否已变为 COMPLETED
     * 5. 如果已变为 COMPLETED：说明第一个请求已执行成功，直接返回缓存的结果（不执行函数）
     * 6. 如果仍为 INPROGRESS：继续等待并重试，直到第一个请求完成或达到最大重试次数
     * 
     * 【重试流程详解】
     * 第一次尝试：
     *   - processIdempotency() -> saveInProgress() 失败（记录已存在）
     *   - getRecord() 获取记录，状态为 INPROGRESS
     *   - handleForStatus() 抛出 IdempotencyAlreadyInProgressException
     * 
     * 第一次重试（等待10ms后）：
     *   - 重新调用 processIdempotency()
     *   - saveInProgress() 仍然失败（记录仍存在）
     *   - getRecord() 获取记录
     *   - 如果第一个请求已完成：状态变为 COMPLETED，返回缓存结果 ✓
     *   - 如果第一个请求仍在执行：状态仍为 INPROGRESS，继续等待
     * 
     * 后续重试（等待20ms, 40ms, 80ms, 160ms）：
     *   - 重复上述过程，直到第一个请求完成或达到最大重试次数
     * 
     * 【为什么需要重试？】
     * 在高并发场景下，多个请求可能同时到达：
     * - 第一个请求：成功保存 INPROGRESS 状态，开始执行函数
     * - 其他请求：发现记录已存在且状态为 INPROGRESS，需要等待第一个请求完成
     * - 重试机制：等待一段时间后重新检查，如果第一个请求已完成，可以直接返回缓存结果
     * - 这样避免了重复执行，提高了效率，同时保证了幂等性
     * 
     * @return 函数执行结果（可能是缓存的结果，也可能是新执行的结果）
     * @throws Throwable 函数执行或幂等性处理过程中可能抛出的异常
     */
    public Object handle() throws Throwable {
        // 无限循环，直到成功或达到最大重试次数
        for (int i = 0; true; i++) {
            try {
                // 处理幂等性逻辑
                // 如果记录状态为 INPROGRESS，会抛出 IdempotencyAlreadyInProgressException
                // 如果记录状态为 COMPLETED，会直接返回缓存的结果
                return processIdempotency();
            } catch (IdempotencyInconsistentStateException e) {
                // 状态不一致异常：记录在保存和读取时状态不一致
                // 这可能是由于并发操作导致的，重试可能会解决
                if (i == MAX_RETRIES) {
                    // 达到最大重试次数，抛出异常
                    throw e;
                }
                // 否则继续循环重试（i 会自增）
            } catch (IdempotencyAlreadyInProgressException e) {
                // 【核心】记录正在执行中异常：另一个请求正在执行相同的操作
                // 这是并发场景下的正常情况，需要等待第一个请求执行成功
                // 
                // 重试的目的：等待第一个请求执行成功，然后返回缓存的结果
                // 重试流程：
                // 1. 等待一段时间（指数退避策略）
                // 2. 重新调用 processIdempotency()
                // 3. 再次获取记录，检查状态是否已变为 COMPLETED
                // 4. 如果已变为 COMPLETED：返回缓存结果（不执行函数）✓
                // 5. 如果仍为 INPROGRESS：继续等待并重试
                if (i < MAX_RETRIES) {
                    try {
                        // 指数退避策略：每次重试等待时间翻倍
                        // 第1次重试：等待 10ms 后，重新检查记录状态
                        // 第2次重试：等待 20ms 后，重新检查记录状态
                        // 第3次重试：等待 40ms 后，重新检查记录状态
                        // 第4次重试：等待 80ms 后，重新检查记录状态
                        // 第5次重试：等待 160ms 后，重新检查记录状态
                        // 
                        // 等待的目的是：给第一个请求足够的时间完成执行
                        // 一旦第一个请求完成，记录状态会变为 COMPLETED，重试就会成功返回缓存结果
                        long sleepTime = (long) (10 * Math.pow(2, i));
                        Thread.sleep(sleepTime);
                        LOG.debug("由于记录正在执行中，等待 {}ms 后重试（尝试 {}/{}），等待第一个请求执行成功", 
                                sleepTime, i + 1, MAX_RETRIES);
                    } catch (InterruptedException ie) {
                        // 线程被中断，恢复中断状态并抛出异常
                        Thread.currentThread().interrupt();
                        throw new IdempotencyPersistenceLayerException("等待执行完成时被中断", ie);
                    }
                    // 继续循环，进行下一次重试
                    // 下一次重试会重新调用 processIdempotency()，检查记录状态是否已变为 COMPLETED
                    continue;
                } else {
                    // 达到最大重试次数，记录警告并抛出异常
                    // 说明第一个请求执行时间太长，超过了所有重试等待时间
                    LOG.warn("达到最大重试次数（{}），记录仍在执行中，抛出异常", MAX_RETRIES);
                    throw e; // 抛出异常，让调用者处理
                }
            }
        }
    }

    /**
     * 处理幂等性逻辑的核心方法
     * 
     * 处理流程：
     * 1. 尝试保存 INPROGRESS 状态到持久化存储
     * 2. 如果保存成功：说明这是第一次请求，执行函数并保存结果
     * 3. 如果保存失败（记录已存在）：获取现有记录并根据状态处理
     * 
     * 可能的执行路径：
     * - 记录不存在：保存 INPROGRESS -> 执行函数 -> 保存 COMPLETED -> 返回结果
     * - 记录存在且状态为 COMPLETED：返回缓存结果（重试成功的情况）
     * - 记录存在且状态为 INPROGRESS：抛出 IdempotencyAlreadyInProgressException（会触发重试）
     * - 记录存在但已过期：重新保存 INPROGRESS -> 执行函数 -> 保存 COMPLETED -> 返回结果
     * 
     * 【重试机制的关键】
     * 这个方法在重试时会被重新调用：
     * 1. 第一次调用：记录状态为 INPROGRESS，抛出异常，触发重试
     * 2. 重试时再次调用：重新获取记录，检查状态是否已变为 COMPLETED
     * 3. 如果已变为 COMPLETED：说明第一个请求已执行成功，返回缓存结果 ✓
     * 4. 如果仍为 INPROGRESS：继续抛出异常，触发下一次重试
     * 
     * 所以重试的本质是：反复调用这个方法，等待第一个请求执行成功（状态变为 COMPLETED）
     * 
     * @return 函数执行结果（可能是缓存的结果，也可能是新执行的结果）
     * @throws Throwable 函数执行或幂等性处理过程中可能抛出的异常
     */
    private Object processIdempotency() throws Throwable {
        try {
            // 尝试保存 INPROGRESS 状态到持久化存储
            // 如果记录不存在，会成功保存；如果记录已存在，会抛出 IdempotencyItemAlreadyExistsException
            persistenceStore.saveInProgress(data, Instant.now(), getRemainingTimeInMillis());
            
            // 如果保存成功，说明这是第一次请求（记录不存在）
            // 继续执行函数并保存结果
        } catch (IdempotencyItemAlreadyExistsException iaee) {
            // 记录已存在异常：说明之前已经处理过相同的请求
            // 需要获取现有记录并根据状态处理
            // 
            // 【重试时的关键】在重试时，这里会重新获取记录
            // 如果第一个请求已执行成功，记录状态会变为 COMPLETED
            // 如果第一个请求仍在执行，记录状态仍为 INPROGRESS
            
            // 尝试从异常中获取记录，如果没有则从持久化存储中获取
            DataRecord dr = iaee.getDataRecord().orElseGet(this::getIdempotencyRecord);
            if (dr != null) {
                // 根据记录的状态进行处理：
                // - COMPLETED：返回缓存结果（重试成功！第一个请求已执行成功）✓
                // - INPROGRESS：抛出 IdempotencyAlreadyInProgressException（会触发重试）
                // - EXPIRED：重新执行（理论上不应该发生，因为 saveInProgress 会检查过期）
                // 
                // 【重试成功的情况】
                // 当重试时，如果记录状态已变为 COMPLETED，说明第一个请求已执行成功
                // handleForStatus() 会返回缓存的结果，不执行函数
                return handleForStatus(dr);
            }
            // 如果记录为 null，说明在获取过程中被删除了，继续执行函数
        } catch (IdempotencyKeyException ike) {
            // 幂等性键异常：无法生成幂等性键（如 payload 为空）
            // 直接抛出，不进行重试
            throw ike;
        } catch (IdempotencyValidationException ive) {
            // 负载验证异常：相同键但负载不同
            // 直接抛出，不进行重试
            throw ive;
        } catch (Exception e) {
            // 其他异常：持久化存储操作失败
            // 包装为 IdempotencyPersistenceLayerException 并抛出
            throw new IdempotencyPersistenceLayerException(
                    "保存 INPROGRESS 状态到幂等性存储失败", e);
        }
        
        // 如果成功保存了 INPROGRESS 状态，执行函数并保存结果
        return getFunctionResponse();
    }

    /**
     * 获取剩余执行时间（毫秒）
     * 
     * 用于设置 INPROGRESS 状态的过期时间
     * 如果函数执行时间超过剩余时间，INPROGRESS 状态会自动过期
     * 这样可以避免因为函数执行失败或超时导致记录永远处于 INPROGRESS 状态
     * 
     * @return 剩余执行时间（毫秒），如果执行上下文不存在则返回空
     */
    private OptionalInt getRemainingTimeInMillis() {
        if (executionContext != null) {
            // 从执行上下文中获取剩余时间
            // 例如：Lambda 函数的剩余执行时间
            return executionContext.getRemainingTimeInMillis();
        }
        // 如果没有执行上下文，返回空（不设置 INPROGRESS 过期时间）
        return OptionalInt.empty();
    }

    /**
     * 从持久化存储中获取幂等性记录
     * 
     * 这个方法在 saveInProgress 抛出 IdempotencyItemAlreadyExistsException 时被调用
     * 用于获取已存在的记录，以便根据状态进行处理
     * 
     * 异常处理：
     * - IdempotencyItemNotFoundException：记录不存在（状态不一致）
     *   这可能发生在并发场景下：saveInProgress 时记录存在，但 getRecord 时记录被删除
     * - IdempotencyValidationException：负载验证失败（相同键但负载不同）
     * - IdempotencyKeyException：无法生成幂等性键
     * 
     * @return 幂等性记录
     * @throws IdempotencyInconsistentStateException 如果记录不存在（状态不一致）
     * @throws IdempotencyValidationException 如果负载验证失败
     * @throws IdempotencyKeyException 如果无法生成幂等性键
     * @throws IdempotencyPersistenceLayerException 如果持久化存储操作失败
     */
    private DataRecord getIdempotencyRecord() {
        try {
            // 从持久化存储中获取记录
            // 传入 payload 和当前时间，用于生成幂等性键和验证过期时间
            return persistenceStore.getRecord(data, Instant.now());
        } catch (IdempotencyItemNotFoundException e) {
            // 记录不存在异常：状态不一致
            // 这可能发生在并发场景下：
            // - saveInProgress 时记录存在（抛出 IdempotencyItemAlreadyExistsException）
            // - 但在 getRecord 时记录被删除（可能是过期或手动删除）
            LOG.debug("在获取幂等性记录之前，记录已被删除（状态不一致）");
            throw new IdempotencyInconsistentStateException(
                    "saveInProgress 和 getRecord 返回不一致的结果（记录在保存和读取之间被删除）", e);
        } catch (IdempotencyValidationException | IdempotencyKeyException vke) {
            // 负载验证异常或键异常：直接抛出，不进行包装
            throw vke;
        } catch (Exception e) {
            // 其他异常：持久化存储操作失败
            throw new IdempotencyPersistenceLayerException(
                    "从幂等性存储获取记录失败", e);
        }
    }

    /**
     * 根据记录状态处理幂等性逻辑
     * 
     * 这个方法在记录已存在时被调用，根据记录的状态进行不同的处理：
     * 
     * 状态处理：
     * 1. EXPIRED（已过期）：抛出状态不一致异常
     *    - 理论上不应该发生，因为 saveInProgress 会检查过期时间
     *    - 如果发生，说明状态不一致
     * 
     * 2. INPROGRESS（执行中）：抛出 IdempotencyAlreadyInProgressException
     *    - 说明另一个请求正在执行相同的操作
     *    - handle() 方法会捕获此异常并进行重试
     *    - 【重试目的】等待第一个请求执行成功，然后返回缓存的结果
     *    - 重试机制允许并发请求等待第一个请求完成
     * 
     * 3. COMPLETED（已完成）：返回缓存的结果
     *    - 说明之前已经执行过相同的请求，且第一个请求已执行成功
     *    - 直接返回缓存的结果，不执行函数
     *    - 这是幂等性的核心：相同请求只执行一次
     *    - 【重试成功】当重试时发现状态已变为 COMPLETED，说明第一个请求已执行成功，返回缓存结果
     * 
     * @param record 已存在的幂等性记录
     * @return 函数执行结果（从缓存中获取）
     * @throws IdempotencyInconsistentStateException 如果记录状态为 EXPIRED（状态不一致）
     * @throws IdempotencyAlreadyInProgressException 如果记录状态为 INPROGRESS（正在执行中）
     * @throws IdempotencyPersistenceLayerException 如果反序列化缓存结果失败
     */
    private Object handleForStatus(DataRecord record) {
        // 检查1：记录状态为 EXPIRED（已过期）
        // 理论上不应该发生，因为 saveInProgress 会检查过期时间
        // 如果发生，说明状态不一致（可能是并发操作导致）
        if (EXPIRED.equals(record.getStatus())) {
            throw new IdempotencyInconsistentStateException(
                    "saveInProgress 和 getRecord 返回不一致的结果（记录状态为 EXPIRED）");
        }

        // 检查2：记录状态为 INPROGRESS（执行中）
        // 说明另一个请求正在执行相同的操作
        // 【重试的核心】这里抛出异常，触发 handle() 方法的重试机制
        // 重试的目的是：等待第一个请求执行成功，然后返回缓存的结果
        if (INPROGRESS.equals(record.getStatus())) {
            // 验证 INPROGRESS 状态的过期时间
            // 如果已过期，说明状态不一致（应该已经被清理）
            if (record.getInProgressExpiryTimestamp().isPresent()
                    && record.getInProgressExpiryTimestamp().getAsLong() < Instant.now().toEpochMilli()) {
                throw new IdempotencyInconsistentStateException(
                        "记录应该已经过期（INPROGRESS 状态已超时），但状态仍为 INPROGRESS");
            }
            // 【关键】抛出 IdempotencyAlreadyInProgressException
            // handle() 方法会捕获此异常并进行重试
            // 
            // 重试流程：
            // 1. handle() 捕获此异常，等待一段时间（指数退避）
            // 2. 重新调用 processIdempotency()
            // 3. 再次获取记录，检查状态是否已变为 COMPLETED
            // 4. 如果已变为 COMPLETED：说明第一个请求已执行成功，返回缓存结果 ✓
            // 5. 如果仍为 INPROGRESS：继续等待并重试
            // 
            // 所以重试的本质是：等待第一个请求执行成功的结果
            throw new IdempotencyAlreadyInProgressException(
                    "执行正在进行中，幂等性键: " + record.getIdempotencyKey());
        }

        // 处理3：记录状态为 COMPLETED（已完成）
        // 【重试成功】说明第一个请求已执行成功，直接返回缓存的结果
        // 
        // 这个分支会在以下两种情况下被调用：
        // 1. 第一次请求：记录不存在，执行函数并保存 COMPLETED 状态
        // 2. 重复请求：记录状态为 COMPLETED，直接返回缓存结果
        // 3. 【重试成功】重试时发现状态已变为 COMPLETED，说明第一个请求已执行成功，返回缓存结果
        // 
        // 重试成功的情况：
        // - 第一次尝试：记录状态为 INPROGRESS，抛出异常，触发重试
        // - 等待一段时间后重试：重新获取记录，状态已变为 COMPLETED
        // - 进入此分支：返回缓存的结果，不执行函数 ✓
        try {
            LOG.debug("从幂等性存储中获取键 '{}' 的响应，跳过函数执行（可能是重试成功，第一个请求已执行完成）",
                    record.getIdempotencyKey());

            // 配置：responseHook - 响应钩子函数
            // 如果配置了，会在返回缓存结果前对响应进行自定义处理
            // 可以转换响应格式、添加额外信息（如时间戳）、记录日志等
            // 如果为null，直接返回缓存的结果（默认行为）
            final BiFunction<Object, DataRecord, Object> responseHook = config.getResponseHook();
            final Object responseData;

            // 根据返回类型反序列化响应数据
            if (String.class.equals(returnTypeRef.getType())) {
                // 如果返回类型是 String，直接使用响应数据（已经是字符串）
                responseData = record.getResponseData();
            } else {
                // 如果返回类型是其他类型，需要从 JSON 反序列化
                // 例如：OrderResponse、Map、List 等
                responseData = JsonConfig.get().getObjectMapper().readValue(record.getResponseData(),
                        returnTypeRef);
            }

            // 如果配置了响应钩子，应用钩子函数
            // 钩子函数可以：
            // - 转换响应格式
            // - 添加额外信息
            // - 记录日志等
            if (responseHook != null) {
                LOG.debug("应用用户定义的响应钩子函数到幂等性数据");
                return responseHook.apply(responseData, record);
            }

            // 如果没有响应钩子，直接返回响应数据
            return responseData;
        } catch (Exception e) {
            // 反序列化失败：可能是类型不匹配或 JSON 格式错误
            throw new IdempotencyPersistenceLayerException(
                    "无法将函数响应反序列化为 " + returnTypeRef.getType().getTypeName(), e);
        }
    }

    /**
     * 执行函数并保存结果
     * 
     * 这个方法在成功保存 INPROGRESS 状态后被调用，表示这是第一次请求
     * 
     * 处理流程：
     * 1. 执行函数（实际业务逻辑）
     * 2. 如果执行成功：保存 COMPLETED 状态和响应数据
     * 3. 如果执行失败：删除 INPROGRESS 记录，然后抛出异常
     * 
     * 为什么执行失败要删除记录？
     * - 如果函数执行失败，不应该保留 INPROGRESS 状态
     * - 删除记录后，相同的请求可以重新执行
     * - 这样可以避免因为函数执行失败导致记录永远处于 INPROGRESS 状态
     * 
     * 为什么执行成功要保存 COMPLETED 状态？
     * - 保存响应数据，以便后续相同请求可以直接返回缓存结果
     * - 这是幂等性的核心：相同请求只执行一次，后续请求返回缓存结果
     * 
     * @return 函数执行结果
     * @throws Throwable 函数执行失败时抛出的异常
     */
    private Object getFunctionResponse() throws Throwable {
        Object response;
        try {
            // 执行函数（实际业务逻辑）
            // 执行调用方传入的幂等函数
            response = function.execute();
        } catch (Throwable handlerException) {
            // 函数执行失败：删除 INPROGRESS 记录
            // 这样相同的请求可以重新执行
            try {
                // 删除记录，传入 payload 和异常信息
                // 删除后，相同的请求可以重新执行（不会因为 INPROGRESS 状态被阻止）
                persistenceStore.deleteRecord(data, handlerException);
            } catch (IdempotencyKeyException ke) {
                // 键异常：无法生成幂等性键，直接抛出
                throw ke;
            } catch (Exception e) {
                // 删除记录失败：包装异常并抛出
                throw new IdempotencyPersistenceLayerException(
                        "从幂等性存储删除记录失败", e);
            }
            // 重新抛出原始异常，让调用者知道函数执行失败
            throw handlerException;
        }

        // 函数执行成功：保存 COMPLETED 状态和响应数据
        try {
            // 保存成功状态和响应数据到持久化存储
            // 这样后续相同请求可以直接返回缓存结果，而不需要重新执行函数
            persistenceStore.saveSuccess(data, response, Instant.now());
        } catch (Exception e) {
            // 保存成功状态失败：包装异常并抛出
            // 注意：函数已经执行成功，但无法保存结果
            // 这可能导致后续相同请求重新执行函数（因为记录状态仍为 INPROGRESS）
            throw new IdempotencyPersistenceLayerException(
                    "更新记录状态为成功失败", e);
        }
        
        // 返回函数执行结果
        return response;
    }
}
