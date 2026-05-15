package com.im.common.lifecycle;

/**
 * 组件生命周期接口。
 *
 * <p>需要启动初始化、关闭清理的基础设施组件应实现此接口。
 * 由 {@link LifecycleManager} 统一编排启动和关闭顺序。
 */
public interface Lifecycle {

    /**
     * 初始化组件。
     *
     * @throws Exception 初始化失败时抛出，由调用方决定是否阻断启动
     */
    default void start() throws Exception {}

    /**
     * 关闭组件，释放资源。
     * <p>实现应保证即使内部状态异常也不抛出（静默处理），
     * 避免影响其他组件的关闭流程。
     */
    default void stop() {}
}
