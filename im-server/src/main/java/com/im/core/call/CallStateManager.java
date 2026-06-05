package com.im.core.call;

import com.im.api.IMessageQueue;
import com.im.api.Message;
import com.im.api.MessageQueueTopics;
import com.im.api.content.ContentType;
import com.im.api.content.SignalingContent;
import com.im.api.SignalingAction;
import com.im.common.id.IdGenerator;
import com.im.common.util.IMExecutors;
import com.im.core.handler.ContentSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 通话超时管理器。
 *
 * <p>追踪活跃通话，在 INVITE 后启动定时器。
 * 若超时未收到 ACCEPT/REJECT，向双方推送 TIMEOUT 信令。</p>
 *
 * <p>线程安全：ConcurrentHashMap.remove() 保证 cancel/fire 不冲突。</p>
 */
public class CallStateManager {

    private static final Logger log = LoggerFactory.getLogger(CallStateManager.class);
    private static final String SYSTEM_USER_ID = "im-system";

    private final ConcurrentHashMap<String, CallSession> activeCalls = new ConcurrentHashMap<>();
    private final ScheduledExecutorService timeoutExecutor;
    private final IMessageQueue messageQueue;
    private final long timeoutSeconds;

    private record CallSession(String callerId, String calleeId, String roomId,
                               ScheduledFuture<?> timeoutFuture) {}

    public CallStateManager(IMessageQueue messageQueue, long timeoutSeconds) {
        this.messageQueue = messageQueue;
        this.timeoutSeconds = timeoutSeconds;
        this.timeoutExecutor = IMExecutors.newScheduledExecutor("im-call-timeout", 1);
        log.info("CallStateManager initialized: timeout={}s", timeoutSeconds);
    }

    /**
     * INVITE 已处理，开始超时计时。
     */
    public void onInvite(String callerId, String calleeId, String roomId) {
        ScheduledFuture<?> future = timeoutExecutor.schedule(
                () -> fireTimeout(roomId, callerId, calleeId),
                timeoutSeconds, TimeUnit.SECONDS);

        activeCalls.put(roomId, new CallSession(callerId, calleeId, roomId, future));
        log.debug("Call timeout scheduled: room={}, caller={}, callee={}, timeout={}s",
                roomId, callerId, calleeId, timeoutSeconds);
    }

    /** 被叫接听。 */
    public void onAccept(String roomId) {
        cancelTimeout(roomId, "ACCEPT");
    }

    /** 被叫拒绝。 */
    public void onReject(String roomId) {
        cancelTimeout(roomId, "REJECT");
    }

    /** 主叫取消。 */
    public void onCancel(String roomId) {
        cancelTimeout(roomId, "CANCEL");
    }

    /** 挂断。 */
    public void onHangup(String roomId) {
        cancelTimeout(roomId, "HANGUP");
    }

    /** 关闭调度器，取消所有待执行超时。 */
    public void shutdown() {
        activeCalls.values().forEach(session -> {
            if (session.timeoutFuture() != null) {
                session.timeoutFuture().cancel(false);
            }
        });
        activeCalls.clear();
        timeoutExecutor.shutdown();
        log.info("CallStateManager shut down, {} pending calls cancelled", activeCalls.size());
    }

    /** 测试用：当前活跃通话数。 */
    public int activeCallCount() {
        return activeCalls.size();
    }

    // ── 私有 ──

    private void cancelTimeout(String roomId, String reason) {
        CallSession session = activeCalls.remove(roomId);
        if (session != null && session.timeoutFuture() != null) {
            session.timeoutFuture().cancel(false);
            log.debug("Call timeout cancelled: room={}, reason={}", roomId, reason);
        }
    }

    private void fireTimeout(String roomId, String callerId, String calleeId) {
        // 原子移除，防止重复执行
        CallSession session = activeCalls.remove(roomId);
        if (session == null) {
            return; // 已经被 ACCEPT/REJECT 等取消
        }

        log.info("Call timeout fired: room={}, caller={}, callee={}", roomId, callerId, calleeId);

        try {
            SignalingContent signal = new SignalingContent(SignalingAction.TIMEOUT, roomId, null);
            byte[] body = ContentSerializer.toBytes(signal);
            String contentStr = new String(body, StandardCharsets.UTF_8);
            long now = System.currentTimeMillis();

            // 发给主叫
            Message callerMsg = Message.createSingle(SYSTEM_USER_ID, callerId, null,
                    ContentType.SIGNAL.getId(), contentStr, 0);
            callerMsg.setMessageId(IdGenerator.messageId());
            callerMsg.setTimestamp(now);
            messageQueue.publishAsync(MessageQueueTopics.DELIVER, callerMsg);

            // 发给被叫
            Message calleeMsg = Message.createSingle(SYSTEM_USER_ID, calleeId, null,
                    ContentType.SIGNAL.getId(), contentStr, 0);
            calleeMsg.setMessageId(IdGenerator.messageId());
            calleeMsg.setTimestamp(now);
            messageQueue.publishAsync(MessageQueueTopics.DELIVER, calleeMsg);

            log.info("Timeout messages sent: room={}, caller={}, callee={}", roomId, callerId, calleeId);
        } catch (Exception e) {
            log.error("Failed to send timeout messages for room={}", roomId, e);
        }
    }
}
