package com.im.api;

/**
 * 生命周期接口。参考 RocketMQ 的 RemotingService。
 *
 * 所有核心组件都实现此接口，统一 start/shutdown 契约。
 */
public interface ILifecycle {

    /**
     * 启动组件。
     * 实现必须幂等（多次调用不产生副作用）。
     */
    void start() throws Exception;

    /**
     * 优雅关闭组件。
     * 实现必须幂等。
     */
    void shutdown() throws Exception;
}
