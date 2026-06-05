package com.im.core.handler.unified;

import com.im.api.ApiRequest;
import com.im.api.ICallManager;
import com.im.api.RequestHandler;
import com.im.api.RoomInformation;
import com.im.api.SignalingAction;
import com.im.api.content.ContentType;
import com.im.api.content.IMessageContent;
import com.im.api.content.SignalingContent;
import com.im.common.exception.UnauthorizedException;
import com.im.common.exception.ValidationException;
import com.im.common.exception.ForbiddenException;
import com.im.core.call.CallStateManager;
import com.im.core.handler.ContentParser;
import com.im.core.usecase.SendMessageResult;
import com.im.core.usecase.SendMessageUseCase;

import java.util.Map;

/**
 * 消息发送 handler：单聊 {@code chat.send}、群聊 {@code chat.send.group}。
 *
 * <p>注意：此 handler 处理的是"发送消息"，
 * 不是"拉取消息"（由 {@code MessageHandler} 处理）。</p>
 *
 * <p>对于音视频通话信令（ContentType.SIGNAL + INVITE），会调用
 * {@link ICallManager#createRoom} 创建 LiveKit 房间并签发 token，
 * 然后将携带有 room token 的 INVITE 转发给被叫。</p>
 */
public class ChatHandler implements RequestHandler {

    private final SendMessageUseCase sendMessageUseCase;
    private final ICallManager callManager;
    private final CallStateManager callStateManager;

    public ChatHandler(SendMessageUseCase sendMessageUseCase, ICallManager callManager,
                       CallStateManager callStateManager) {
        this.sendMessageUseCase = sendMessageUseCase;
        this.callManager = callManager;
        this.callStateManager = callStateManager;
    }

    @Override
    public Object handle(ApiRequest req) {
        String uid = req.currentUserId();
        if (uid == null) {
            throw new UnauthorizedException("not authenticated");
        }
        String toUserId = req.getString("toUserId");
        String groupId = req.getString("groupId");

        // 解析消息内容
        IMessageContent content;
        try {
            content = ContentParser.parse(req.params(), req.bodyRaw());
        } catch (Exception e) {
            throw new ValidationException("invalid content: " + e.getMessage());
        }
        if (content == null) {
            throw new ValidationException("content type (_ct) is required");
        }

        // ── 音视频通话信令处理 ──
        if (content.getContentType() == ContentType.SIGNAL && callManager != null) {
            SignalingContent signal = (SignalingContent) content;
            if (signal.getAction() == SignalingAction.INVITE) {
                if (groupId != null) {
                    throw new ValidationException("group call not supported yet");
                }
                return handleInvite(req.params(), uid, toUserId, signal);
            }
            // 非 INVITE 信令（ACCEPT/REJECT/CANCEL/HANGUP）追踪超时
            if (callStateManager != null) {
                handleSignalTracking(signal);
            }
        }

        // ── 普通消息发送 ──
        SendMessageResult result = sendMessageUseCase.execute(
                req.params(), uid, toUserId, groupId, content);

        if (result == null) {
            throw new ForbiddenException("message sending blocked");
        }

        return Map.of("status", "RECEIVED",
                "conversationId", result.conversationId(),
                "seq", result.seq());
    }

    /**
     * 处理 INVITE 信令：创建 LiveKit 房间，签发 token，
     * 将携带有 room token 的 INVITE 转发给被叫。
     */
    private Object handleInvite(Map<String, Object> params, String callerId,
                                String calleeId, SignalingContent signal) {
        // 创建 LiveKit 房间并签发 token
        RoomInformation room = callManager.createRoom(callerId, calleeId, null);

        // 构建携带有 room 信息的 INVITE 消息，转发给被叫
        SignalingContent inviteContent = new SignalingContent(
                SignalingAction.CALLING, normalizeCallType(signal.getCallType()), room.getRoomId(), room.getCalleeToken(),
                signal.getSdp(), null, 0);

        sendMessageUseCase.execute(params, callerId, calleeId, null, inviteContent);

        // 开始超时计时
        if (callStateManager != null) {
            callStateManager.onInvite(callerId, calleeId, room.getRoomId());
        }

        // 返回 room 信息给主叫（含 callerToken），主叫据此加入房间
        return Map.of(
                "status", "CALLING",
                "roomId", room.getRoomId(),
                "token", room.getCallerToken(),
                "sfuEndpoint", room.getSfuEndpoint());
    }

    /**
     * 追踪非 INVITE 信令用于超时取消。
     * ACCEPT/REJECT/CANCEL/HANGUP 到达时取消超时定时器。
     */
    private void handleSignalTracking(SignalingContent signal) {
        String roomId = signal.getRoomId();
        if (roomId == null || roomId.isBlank()) return;

        switch (signal.getAction()) {
            case ACCEPT -> callStateManager.onAccept(roomId);
            case REJECT -> callStateManager.onReject(roomId);
            case CANCEL -> callStateManager.onCancel(roomId);
            case HANGUP -> callStateManager.onHangup(roomId);
            default -> {} // INVITE/CALLING/TIMEOUT/ICE: 无需处理
        }
    }

    private String normalizeCallType(String callType) {
        return "video".equalsIgnoreCase(callType) ? "video" : "voice";
    }
}
