package com.im.core.util;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * 统一线程池工具类。
 *
 * 线程模型规范：
 *   ┌─────────────────┬──────────────────────┬──────────────────────────────┐
 *   │ 用途            │ 创建方式             │ 适用场景                     │
 *   ├─────────────────┼──────────────────────┼──────────────────────────────┤
 *   │ 业务处理        │ newVirtualThread     │ 消息路由、handler 分发       │
 *   │                 │   Executor           │ 按任务创建虚线程，不阻塞 IO  │
 *   ├─────────────────┼──────────────────────┼──────────────────────────────┤
 *   │ 定时/调度       │ newScheduledExecutor │ 心跳检测、超时扫描、定时重试 │
 *   │                 │                      │ 少量守护线程，保证调度精度   │
 *   └─────────────────┴──────────────────────┴──────────────────────────────┘
 */
public class IMExecutors {

    private IMExecutors() {}

    /**
     * 创建虚拟线程执行器。
     * 每个任务创建一条虚拟线程，适合大量短任务（消息处理、事件分发）。
     * 不限制并发数（虚拟线程由 JVM 调度，成本极低）。
     *
     * @param namePrefix 线程名前缀（如 "im-business"）
     * @return 虚拟线程执行器
     */
    public static ExecutorService newVirtualThreadExecutor(String namePrefix) {
        return Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual()
                        .name(namePrefix + "-", 0)
                        .factory()
        );
    }

    /**
     * 创建一个平台守护线程的定时调度器。
     * 适合心跳发送、超时检查、定期扫描等需要精确调度的场景。
     * 使用平台线程而非虚拟线程——虚拟线程不适合 {@code Thread.sleep()} 以外的阻塞调度。
     *
     * @param namePrefix 线程名前缀（如 "im-heartbeat"）
     * @param coreSize   core pool size（通常 1 即可）
     * @return 定调度器
     */
    public static ScheduledExecutorService newScheduledExecutor(String namePrefix, int coreSize) {
        return Executors.newScheduledThreadPool(coreSize,
                Thread.ofPlatform()
                        .name(namePrefix + "-scheduler-", 0)
                        .daemon(true)
                        .factory()
        );
    }
}
