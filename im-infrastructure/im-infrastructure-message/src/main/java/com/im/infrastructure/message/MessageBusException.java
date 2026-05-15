package com.im.infrastructure.message;

/**
 * 消息中间件异常。
 *
 * <p>让调用方区分"消息投递失败"与业务内部异常，
 * 避免将中间件错误当作业务异常处理。
 */
public class MessageBusException extends RuntimeException {

    public MessageBusException(String message) {
        super(message);
    }

    public MessageBusException(String message, Throwable cause) {
        super(message, cause);
    }
}
