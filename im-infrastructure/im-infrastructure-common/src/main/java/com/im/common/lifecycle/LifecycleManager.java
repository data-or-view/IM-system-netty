package com.im.common.lifecycle;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 生命周期管理器。
 *
 * <p>统一管理组件的启动和关闭：
 * <ul>
 *   <li>启动：按给定顺序依次调用 {@link Lifecycle#start()}</li>
 *   <li>关闭：逆序依次调用 {@link Lifecycle#stop()}，单个失败不影响其他</li>
 * </ul>
 *
 * <pre>{@code
 * LifecycleManager.startAll(components);
 * LifecycleManager.registerShutdownHook(components);
 * }</pre>
 */
public final class LifecycleManager {

    private static final Logger log = LoggerFactory.getLogger(LifecycleManager.class);

    private LifecycleManager() {}

    /**
     * 按顺序启动所有组件。
     *
     * @throws Exception 任一组件启动失败时抛出，后续组件不会启动
     */
    public static void startAll(List<? extends Lifecycle> components) throws Exception {
        for (Lifecycle c : components) {
            c.start();
            log.debug("Started: {}", c.getClass().getSimpleName());
        }
    }

    /**
     * 逆序关闭所有组件。单个组件关闭失败不影响其他。
     */
    public static void stopAll(List<? extends Lifecycle> components) {
        for (int i = components.size() - 1; i >= 0; i--) {
            try {
                components.get(i).stop();
                log.debug("Stopped: {}", components.get(i).getClass().getSimpleName());
            } catch (Exception e) {
                log.warn("Error stopping {}: {}",
                        components.get(i).getClass().getSimpleName(), e.toString());
            }
        }
    }

    /**
     * 注册 JVM 关闭钩子，应用退出时逆序关闭所有组件。
     */
    public static void registerShutdownHook(List<? extends Lifecycle> components) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down {} components...", components.size());
            stopAll(components);
        }));
    }
}
