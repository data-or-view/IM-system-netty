package com.im.common.retry;

/**
 * 重试耗尽异常——所有尝试均失败时抛出。
 */
public class RetryExecutionException extends RuntimeException {

    public RetryExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
