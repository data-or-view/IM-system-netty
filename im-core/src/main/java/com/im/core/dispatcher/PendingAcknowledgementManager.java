package com.im.core.dispatcher;

import com.im.api.IMCommand;
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
 *   onAckReceived(command)  — 收到响应后配对
 *   failFastAll()            — 连接断开时快速失败
 *
 * 等待超时清理由独立的 ScheduledExecutor 驱动（平台守护线程）。
 */
public class PendingAcknowledgementManager {

    private static final Logger log = LoggerFactory.getLogger(PendingAcknowledgementManager.class);

    /** seqId → CompletableFuture */
    private final ConcurrentHashMap<Integer, CompletableFuture<IMCommand>> pendingTable = new ConcurrentHashMap<>();

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
    public void register(int seqId, CompletableFuture<IMCommand> future, long timeoutMs) {
        pendingTable.put(seqId, future);
        timeoutExecutor.schedule(() -> {
            CompletableFuture<IMCommand> f = pendingTable.remove(seqId);
            if (f != null && !f.isDone()) {
                f.completeExceptionally(new TimeoutException("ACK timeout for seqId=" + seqId));
                timeoutCount.incrementAndGet();
            }
        }, timeoutMs, TimeUnit.MILLISECONDS);
    }

    /**
     * 收到 ACK 时配对。
     *
     * @param command ACK 消息
     */
    public void onAckReceived(IMCommand command) {
        CompletableFuture<IMCommand> future = pendingTable.remove(command.getSeqId());
        if (future != null) {
            future.complete(command);
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
            CompletableFuture<IMCommand> f = pendingTable.remove(seqId);
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
