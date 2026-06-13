package com.wzg.idempotency.exception;

/**
 * 幂等性验证异常
 * 
 * <p>保留该异常类型是为了兼容早期 API。当前无表达式版本的核心逻辑不再执行独立的 payload
 * validation；重复请求只通过调用方传入的完整 idempotencyKey 对象生成哈希并命中记录。</p>
 * 
 * @see com.wzg.idempotency.config.IdempotencyConfig
 * @see com.wzg.idempotency.persistence.BasePersistenceStore
 */
public class IdempotencyValidationException extends RuntimeException {
    private static final long serialVersionUID = -4218652810664634761L;

    public IdempotencyValidationException() {
        super();
    }

    public IdempotencyValidationException(String message) {
        super(message);
    }
}
