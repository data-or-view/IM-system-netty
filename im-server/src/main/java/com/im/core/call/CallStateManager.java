package com.im.core.call;

import com.im.api.IMessageQueue;
import com.im.api.Message;
import com.im.api.MessageQueueTopics;
import com.im.api.content.ContentType;
import com.im.api.content.SignalingContent;
import com.im.api.SignalingAction;
import com.im.common.exception.ForbiddenException;
import com.im.common.exception.NotFoundException;
import com.im.common.exception.ValidationException;
import com.im.common.util.IMExecutors;
import com.im.core.handler.ContentSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.UUID;

/**
 * 通话超时管理器。
 *
 * <p>每个节点扫描 Redis 中到期的响铃通话。Redis 原子 claim 决定唯一的超时发布者，
 * 因此创建节点停止后，其他节点仍可恢复超时处理。</p>
 */
public class CallStateManager {

    private static final Logger log = LoggerFactory.getLogger(CallStateManager.class);
    private static final String SYSTEM_USER_ID = "im-system";

    private final ScheduledExecutorService timeoutExecutor;
    private final IMessageQueue messageQueue;
    private final SingleCallStateStore stateStore;
    private final long timeoutSeconds;
    private final int timeoutBatchSize;

    public CallStateManager(IMessageQueue messageQueue, SingleCallStateStore stateStore, long timeoutSeconds) {
        this(messageQueue, stateStore, timeoutSeconds, 1_000L, 100);
    }

    public CallStateManager(IMessageQueue messageQueue, SingleCallStateStore stateStore, long timeoutSeconds,
                            long timeoutScanIntervalMillis, int timeoutBatchSize) {
        this.messageQueue = messageQueue;
        this.stateStore = stateStore;
        this.timeoutSeconds = timeoutSeconds;
        this.timeoutBatchSize = timeoutBatchSize;
        this.timeoutExecutor = IMExecutors.newScheduledExecutor("im-call-timeout", 1);
        this.timeoutExecutor.scheduleWithFixedDelay(this::safeScanExpiredCalls,
                Math.max(1L, timeoutScanIntervalMillis), Math.max(1L, timeoutScanIntervalMillis), TimeUnit.MILLISECONDS);
        log.info("CallStateManager initialized: timeout={}s, scanInterval={}ms, batchSize={}",
                timeoutSeconds, timeoutScanIntervalMillis, timeoutBatchSize);
    }

    public SingleCallSession createRinging(String callerId, String calleeId, String callType,
                                           String roomId, String sfuEndpoint) {
        long now = System.currentTimeMillis();
        SingleCallSession session = new SingleCallSession(roomId, callerId, calleeId, callType,
                SingleCallSession.STATUS_RINGING, sfuEndpoint, now, 0,
                now + TimeUnit.SECONDS.toMillis(timeoutSeconds));
        return stateStore.createIfUsersIdle(session);
    }

    public SingleCallSession getActiveByUser(String userId) {
        return stateStore.getActiveByUser(userId);
    }

    /** 被叫接听。 */
    public void onAccept(String roomId) {
        stateStore.accept(roomId);
    }

    public void requireCanSendSignal(String actorId, String peerUserId, SignalingContent signal) {
        String roomId = requireRoomId(signal);
        SingleCallSession session = stateStore.getByRoom(roomId);
        if (session == null) {
            throw new NotFoundException("call session not found");
        }
        if (SingleCallSession.STATUS_TIMED_OUT.equals(session.status())) {
            throw new NotFoundException("call session has timed out");
        }
        requireParticipant(session, actorId);
        if (peerUserId != null && !peerUserId.isBlank() && !isParticipant(session, peerUserId)) {
            throw new ForbiddenException("call peer is not a participant");
        }
        switch (signal.getAction()) {
            case ACCEPT, REJECT -> {
                if (!actorId.equals(session.calleeId())) {
                    throw new ForbiddenException("only callee can " + signal.getAction().name().toLowerCase() + " call");
                }
            }
            case CANCEL -> {
                if (!actorId.equals(session.callerId())) {
                    throw new ForbiddenException("only caller can cancel call");
                }
            }
            case HANGUP, ICE -> {
                // Any participant can hang up or exchange ICE.
            }
            default -> {
                // CALLING/TIMEOUT are system/server-side states and must not be client-mutated here.
                throw new ValidationException("unsupported call signal action");
            }
        }
    }

    public void onSignalDelivered(String actorId, SignalingContent signal) {
        String roomId = requireRoomId(signal);
        switch (signal.getAction()) {
            case ACCEPT -> {
                stateStore.acceptBy(roomId, actorId);
            }
            case REJECT -> {
                stateStore.endBy(roomId, actorId);
            }
            case CANCEL -> {
                stateStore.endBy(roomId, actorId);
            }
            case HANGUP -> {
                stateStore.endBy(roomId, actorId);
            }
            default -> {
                // ICE keeps the call state unchanged.
            }
        }
    }

    /** 被叫拒绝。 */
    public void onReject(String roomId) {
        stateStore.end(roomId);
    }

    /** 主叫取消。 */
    public void onCancel(String roomId) {
        stateStore.end(roomId);
    }

    /** 挂断。 */
    public void onHangup(String roomId) {
        stateStore.end(roomId);
    }

    /** 关闭本节点的扫描器；共享 Redis deadline remains available to other nodes. */
    public void shutdown() {
        timeoutExecutor.shutdown();
        log.info("CallStateManager timeout scanner shut down");
    }

    /** 测试用：当前活跃通话数。 */
    public int activeCallCount() {
        return 0;
    }

    public void scanExpiredCalls() {
        for (SingleCallSession session : stateStore.claimExpiredRinging(System.currentTimeMillis(), timeoutBatchSize)) {
            publishTimeoutOnce(session);
        }
    }

    private String requireRoomId(SignalingContent signal) {
        String roomId = signal != null ? signal.getRoomId() : null;
        if (roomId == null || roomId.isBlank()) {
            throw new ValidationException("roomId is required for call signal");
        }
        return roomId;
    }

    private void requireParticipant(SingleCallSession session, String userId) {
        if (!isParticipant(session, userId)) {
            throw new ForbiddenException("call signal sender is not a participant");
        }
    }

    private boolean isParticipant(SingleCallSession session, String userId) {
        return userId != null && (userId.equals(session.callerId()) || userId.equals(session.calleeId()));
    }

    private void safeScanExpiredCalls() {
        try {
            scanExpiredCalls();
        } catch (Exception e) {
            log.error("Failed to scan expired calls", e);
        }
    }

    private void publishTimeoutOnce(SingleCallSession session) {
        String roomId = session.roomId();
        log.info("Call timeout claimed: room={}, caller={}, callee={}", roomId, session.callerId(), session.calleeId());

        try {
            SignalingContent signal = new SignalingContent(SignalingAction.TIMEOUT, roomId, null);
            signal.setReason("timeout");
            byte[] body = ContentSerializer.toBytes(signal);
            String contentStr = new String(body, StandardCharsets.UTF_8);
            long now = System.currentTimeMillis();

            // 发给主叫
            Message callerMsg = Message.createSingle(SYSTEM_USER_ID, session.callerId(), null,
                    ContentType.SIGNAL.getId(), contentStr, 0);
            callerMsg.setMessageId(timeoutMessageId(roomId, session.callerId()));
            callerMsg.setTimestamp(now);
            messageQueue.publish(MessageQueueTopics.DELIVER, callerMsg);

            // 发给被叫
            Message calleeMsg = Message.createSingle(SYSTEM_USER_ID, session.calleeId(), null,
                    ContentType.SIGNAL.getId(), contentStr, 0);
            calleeMsg.setMessageId(timeoutMessageId(roomId, session.calleeId()));
            calleeMsg.setTimestamp(now);
            messageQueue.publish(MessageQueueTopics.DELIVER, calleeMsg);

            log.info("Timeout messages sent: room={}, caller={}, callee={}", roomId, session.callerId(), session.calleeId());
        } catch (Exception e) {
            log.error("Failed to send timeout messages for room={}", roomId, e);
        }
    }

    private static String timeoutMessageId(String roomId, String recipientId) {
        return UUID.nameUUIDFromBytes(("single-call-timeout:" + roomId + ':' + recipientId)
                .getBytes(StandardCharsets.UTF_8)).toString();
    }
}
