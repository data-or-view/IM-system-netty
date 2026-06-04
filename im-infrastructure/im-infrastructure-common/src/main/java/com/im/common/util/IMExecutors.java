package com.im.common.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;

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
public final class IMExecutors {

    private static final Logger log = LoggerFactory.getLogger(IMExecutors.class);

    private IMExecutors() {}

    private static final Thread.UncaughtExceptionHandler UNCAUGHT_EXCEPTION_HANDLER =
            (thread, error) -> log.error("Uncaught exception in thread {}", thread.getName(), error);

    /**
     * 创建虚拟线程执行器。
     * 每个任务创建一条虚拟线程，适合大量短任务（消息处理、事件分发）。
     * 不限制并发数（虚拟线程由 JVM 调度，成本极低）。
     *
     * @param namePrefix 线程名前缀（如 "im-business"）
     * @return 虚拟线程执行器
     */
    public static ExecutorService newVirtualThreadExecutor(String namePrefix) {
        requireNamePrefix(namePrefix);
        return Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual()
                        .name(namePrefix + "-", 0)
                        .uncaughtExceptionHandler(UNCAUGHT_EXCEPTION_HANDLER)
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
     * @return 定时调度器
     */
    public static ScheduledExecutorService newScheduledExecutor(String namePrefix, int coreSize) {
        requireNamePrefix(namePrefix);
        if (coreSize <= 0) {
            throw new IllegalArgumentException("coreSize must be > 0");
        }
        return new ObservedScheduledThreadPoolExecutor(coreSize,
                Thread.ofPlatform()
                        .name(namePrefix + "-scheduler-", 0)
                        .daemon(true)
                        .uncaughtExceptionHandler(UNCAUGHT_EXCEPTION_HANDLER)
                        .factory());
    }

    private static void requireNamePrefix(String namePrefix) {
        if (namePrefix == null || namePrefix.isBlank()) {
            throw new IllegalArgumentException("namePrefix must not be blank");
        }
    }

    private static final class ObservedScheduledThreadPoolExecutor extends ScheduledThreadPoolExecutor {
        private ObservedScheduledThreadPoolExecutor(int corePoolSize, ThreadFactory threadFactory) {
            super(corePoolSize, threadFactory);
            setRemoveOnCancelPolicy(true);
        }

        @Override
        protected void afterExecute(Runnable runnable, Throwable error) {
            super.afterExecute(runnable, error);
            Throwable actual = error;
            if (actual == null && runnable instanceof Future<?> future && future.isDone()) {
                try {
                    future.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    actual = e;
                } catch (Exception e) {
                    actual = e.getCause() != null ? e.getCause() : e;
                }
            }
            if (actual != null) {
                log.error("Scheduled task failed in {}", Thread.currentThread().getName(), actual);
            }
        }
    }
}
