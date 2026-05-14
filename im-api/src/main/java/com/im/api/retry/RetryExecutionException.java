package com.im.api.retry;

/**
 * 重试耗尽异常——所有尝试均失败时抛出。
 *
 * <p>包装最后一次异常，保留完整堆栈。</p>
 */
public class RetryExecutionException extends RuntimeException {

    public RetryExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
