package com.im.core.dispatcher;

import com.im.api.CommandType;
import com.im.api.IMCommand;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PendingAcknowledgementManager 测试：ACK 配对、超时、快速失败。
 */
class PendingAcknowledgementManagerTest {

    private PendingAcknowledgementManager manager;

    @BeforeEach
    void setUp() {
        manager = new PendingAcknowledgementManager();
    }

    @AfterEach
    void tearDown() {
        manager.shutdown();
    }

    @Test
    void registerAndComplete() throws Exception {
        IMCommand request = new IMCommand(CommandType.SINGLE_CHAT);
        CompletableFuture<IMCommand> future = new CompletableFuture<>();

        manager.register(request.getSeqId(), future, 5000);

        IMCommand ack = request.createAcknowledgement(CommandType.SINGLE_CHAT_ACK);
        manager.onAckReceived(ack);

        assertTrue(future.isDone());
        assertFalse(future.isCompletedExceptionally());
        assertEquals(ack, future.get(1, TimeUnit.SECONDS));
        assertEquals(0, manager.pendingCount());
    }

    @Test
    void registerTimeout() throws Exception {
        CompletableFuture<IMCommand> future = new CompletableFuture<>();
        manager.register(12345, future, 100); // 100ms 超时

        try {
            future.get(5000, TimeUnit.MILLISECONDS);
            fail("Should have thrown");
        } catch (ExecutionException e) {
            assertInstanceOf(java.util.concurrent.TimeoutException.class, e.getCause());
        }
        assertEquals(1, manager.timeoutCount());
    }

    @Test
    void failFastAllCompletesExceptionally() {
        CompletableFuture<IMCommand> f1 = new CompletableFuture<>();
        CompletableFuture<IMCommand> f2 = new CompletableFuture<>();
        manager.register(1, f1, 5000);
        manager.register(2, f2, 5000);

        manager.failFastAll();

        assertTrue(f1.isCompletedExceptionally());
        assertTrue(f2.isCompletedExceptionally());
        assertEquals(0, manager.pendingCount());
    }

    @Test
    void onAckReceivedUnknownSeqId() {
        // 收到未知 seqId 的 ACK 应该安全地忽略
        IMCommand ack = new IMCommand(CommandType.HEARTBEAT_ACK);
        ack.setSeqId(99999);
        manager.onAckReceived(ack);
        assertEquals(0, manager.pendingCount());
    }

    @Test
    void pendingCount() {
        assertEquals(0, manager.pendingCount());
        manager.register(1, new CompletableFuture<>(), 5000);
        assertEquals(1, manager.pendingCount());
        manager.register(2, new CompletableFuture<>(), 5000);
        assertEquals(2, manager.pendingCount());
    }

    @Test
    void timeoutCount() throws Exception {
        assertEquals(0, manager.timeoutCount());
        CompletableFuture<IMCommand> f1 = new CompletableFuture<>();
        manager.register(1, f1, 100);
        try { f1.get(2000, TimeUnit.MILLISECONDS); } catch (Exception ignored) {}
        // 等待超时任务更新计数
        Thread.sleep(50);
        assertEquals(1, manager.timeoutCount());
    }

    @Test
    void shutdownFailsAll() {
        manager.register(1, new CompletableFuture<>(), 5000);
        manager.register(2, new CompletableFuture<>(), 5000);

        manager.shutdown();

        assertEquals(0, manager.pendingCount());
    }
}
