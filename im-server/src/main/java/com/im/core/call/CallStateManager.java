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
 * <p>通话状态以 Redis store 为准，本地 map 只保存当前节点创建的超时任务。</p>
 */
public class CallStateManager {

    private static final Logger log = LoggerFactory.getLogger(CallStateManager.class);
    private static final String SYSTEM_USER_ID = "im-system";

    private final ConcurrentHashMap<String, CallSession> activeCalls = new ConcurrentHashMap<>();
    private final ScheduledExecutorService timeoutExecutor;
    private final IMessageQueue messageQueue;
    private final SingleCallStateStore stateStore;
    private final long timeoutSeconds;

    private record CallSession(String callerId, String calleeId, String roomId,
                               ScheduledFuture<?> timeoutFuture) {}

    public CallStateManager(IMessageQueue messageQueue, SingleCallStateStore stateStore, long timeoutSeconds) {
        this.messageQueue = messageQueue;
        this.stateStore = stateStore;
        this.timeoutSeconds = timeoutSeconds;
        this.timeoutExecutor = IMExecutors.newScheduledExecutor("im-call-timeout", 1);
        log.info("CallStateManager initialized: timeout={}s", timeoutSeconds);
    }

    public SingleCallSession createRinging(String callerId, String calleeId, String callType,
                                           String roomId, String sfuEndpoint) {
        long now = System.currentTimeMillis();
        SingleCallSession session = new SingleCallSession(roomId, callerId, calleeId, callType,
                SingleCallSession.STATUS_RINGING, sfuEndpoint, now, 0);
        SingleCallSession created = stateStore.createIfUsersIdle(session);
        if (created != null) {
            scheduleTimeout(created);
        }
        return created;
    }

    public SingleCallSession getActiveByUser(String userId) {
        return stateStore.getActiveByUser(userId);
    }

    /**
     * INVITE 已处理，开始超时计时。
     */
    public void onInvite(String callerId, String calleeId, String roomId) {
        scheduleTimeout(new SingleCallSession(roomId, callerId, calleeId, "voice",
                SingleCallSession.STATUS_RINGING, null, System.currentTimeMillis(), 0));
    }

    private void scheduleTimeout(SingleCallSession session) {
        ScheduledFuture<?> future = timeoutExecutor.schedule(
                () -> fireTimeout(session.roomId()),
                timeoutSeconds, TimeUnit.SECONDS);

        activeCalls.put(session.roomId(), new CallSession(session.callerId(), session.calleeId(), session.roomId(), future));
        log.debug("Call timeout scheduled: room={}, caller={}, callee={}, timeout={}s",
                session.roomId(), session.callerId(), session.calleeId(), timeoutSeconds);
    }

    /** 被叫接听。 */
    public void onAccept(String roomId) {
        cancelTimeout(roomId, "ACCEPT");
        stateStore.accept(roomId);
    }

    public void requireCanSendSignal(String actorId, String peerUserId, SignalingContent signal) {
        String roomId = requireRoomId(signal);
        SingleCallSession session = stateStore.getByRoom(roomId);
        if (session == null) {
            throw new NotFoundException("call session not found");
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
                cancelTimeout(roomId, "ACCEPT");
                stateStore.acceptBy(roomId, actorId);
            }
            case REJECT -> {
                cancelTimeout(roomId, "REJECT");
                stateStore.endBy(roomId, actorId);
            }
            case CANCEL -> {
                cancelTimeout(roomId, "CANCEL");
                stateStore.endBy(roomId, actorId);
            }
            case HANGUP -> {
                cancelTimeout(roomId, "HANGUP");
                stateStore.endBy(roomId, actorId);
            }
            default -> {
                // ICE keeps the call state unchanged.
            }
        }
    }

    /** 被叫拒绝。 */
    public void onReject(String roomId) {
        cancelTimeout(roomId, "REJECT");
        stateStore.end(roomId);
    }

    /** 主叫取消。 */
    public void onCancel(String roomId) {
        cancelTimeout(roomId, "CANCEL");
        stateStore.end(roomId);
    }

    /** 挂断。 */
    public void onHangup(String roomId) {
        cancelTimeout(roomId, "HANGUP");
        stateStore.end(roomId);
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

    private void fireTimeout(String roomId) {
        activeCalls.remove(roomId);
        SingleCallSession session = stateStore.timeoutIfRinging(roomId);
        if (session == null) return;

        log.info("Call timeout fired: room={}, caller={}, callee={}", roomId, session.callerId(), session.calleeId());

        try {
            SignalingContent signal = new SignalingContent(SignalingAction.TIMEOUT, roomId, null);
            signal.setReason("timeout");
            byte[] body = ContentSerializer.toBytes(signal);
            String contentStr = new String(body, StandardCharsets.UTF_8);
            long now = System.currentTimeMillis();

            // 发给主叫
            Message callerMsg = Message.createSingle(SYSTEM_USER_ID, session.callerId(), null,
                    ContentType.SIGNAL.getId(), contentStr, 0);
            callerMsg.setMessageId(IdGenerator.messageId());
            callerMsg.setTimestamp(now);
            messageQueue.publish(MessageQueueTopics.DELIVER, callerMsg);

            // 发给被叫
            Message calleeMsg = Message.createSingle(SYSTEM_USER_ID, session.calleeId(), null,
                    ContentType.SIGNAL.getId(), contentStr, 0);
            calleeMsg.setMessageId(IdGenerator.messageId());
            calleeMsg.setTimestamp(now);
            messageQueue.publish(MessageQueueTopics.DELIVER, calleeMsg);

            log.info("Timeout messages sent: room={}, caller={}, callee={}", roomId, session.callerId(), session.calleeId());
        } catch (Exception e) {
            log.error("Failed to send timeout messages for room={}", roomId, e);
        }
    }
}
