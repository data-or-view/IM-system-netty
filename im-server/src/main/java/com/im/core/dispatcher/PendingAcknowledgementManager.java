package com.im.core.dispatcher;

import com.im.common.util.IMExecutors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ACK 等待管理器，参考 RocketMQ 的 responseTable + ResponseFuture。
 *
 * 核心逻辑：
 *   register(seqId, future) — 发送请求前注册
 *   onAckReceived(seqId)   — 收到响应后配对
 *   failFastAll()           — 连接断开时快速失败
 *
 * 等待超时清理由独立的 ScheduledExecutor 驱动（平台守护线程）。
 */
public class PendingAcknowledgementManager {

    private static final Logger log = LoggerFactory.getLogger(PendingAcknowledgementManager.class);

    /** seqId → CompletableFuture */
    private final ConcurrentHashMap<Integer, CompletableFuture<Integer>> pendingTable = new ConcurrentHashMap<>();

    /** 超时调度器：平台守护线程 */
    private final ScheduledExecutorService timeoutExecutor;
    private final AtomicInteger timeoutCount = new AtomicInteger(0);

    public PendingAcknowledgementManager() {
        this.timeoutExecutor = IMExecutors.newScheduledExecutor("im-ack-timeout", 1);
    }

    /**
     * 注册等待中的 ACK。
     *
     * @param seqId    请求序列号
     * @param future   ACK 到达时完成的 CompletableFuture
     * @param timeoutMs 超时时间（毫秒）
     */
    public void register(int seqId, CompletableFuture<Integer> future, long timeoutMs) {
        pendingTable.put(seqId, future);
        timeoutExecutor.schedule(() -> {
            CompletableFuture<Integer> f = pendingTable.remove(seqId);
            if (f != null && !f.isDone()) {
                f.completeExceptionally(new TimeoutException("ACK timeout for seqId=" + seqId));
                timeoutCount.incrementAndGet();
            }
        }, timeoutMs, TimeUnit.MILLISECONDS);
    }

    /**
     * 收到 ACK 时配对。
     *
     * @param seqId ACK 对应的请求序列号
     */
    public void onAckReceived(int seqId) {
        CompletableFuture<Integer> future = pendingTable.remove(seqId);
        if (future != null) {
            future.complete(seqId);
        }
    }

    /**
     * 快速失败所有等待中的 ACK（连接断开时调用）。
     */
    public void failFastAll() {
        if (pendingTable.isEmpty()) {
            return;
        }
        int count = 0;
        for (Integer seqId : pendingTable.keySet()) {
            CompletableFuture<Integer> f = pendingTable.remove(seqId);
            if (f != null && !f.isDone()) {
                f.completeExceptionally(new IllegalStateException("Connection closed, seqId=" + seqId));
                count++;
            }
        }
        if (count > 0) {
            log.info("failFastAll: completed {} pending ACKs exceptionally", count);
        }
    }

    /** 当前等待的 ACK 数量 */
    public int pendingCount() {
        return pendingTable.size();
    }

    /** 累计超时的 ACK 数量 */
    public int timeoutCount() {
        return timeoutCount.get();
    }

    /** 关闭调度器 + 快速失败所有等待 */
    public void shutdown() {
        timeoutExecutor.shutdown();
        failFastAll();
    }
}
