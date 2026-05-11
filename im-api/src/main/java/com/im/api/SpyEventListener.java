package com.im.api;

/**
 * 可观测性事件监听器接口。
 * 用于监控关键事件（连接/断开/异常/消息投递），不影响业务逻辑。
 *
 * 类比 RocketMQ 的 NettyEvent / NettyEventExecutor 的异步通知模式。
 * 实现可以是日志、Metrics、监控等。
 */
@FunctionalInterface
public interface SpyEventListener {

    /**
     * 事件类型。
     */
    enum EventType {
        CHANNEL_CONNECTED,
        CHANNEL_DISCONNECTED,
        USER_LOGIN,
        USER_LOGOUT,
        MESSAGE_SENT,
        MESSAGE_RECEIVED,
        MESSAGE_DELIVERED,
        EXCEPTION_CAUGHT,
        CHANNEL_IDLE_CLOSED,
    }

    /**
     * 事件数据。
     */
    record SpyEvent(
            EventType type,
            long timestamp,
            String sessionId,
            String userId,
            String remoteAddr,
            String detail
    ) {}

    /**
     * 处理事件。
     */
    void onEvent(SpyEvent event);
}
