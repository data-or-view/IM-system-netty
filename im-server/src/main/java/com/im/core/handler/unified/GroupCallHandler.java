package com.im.core.handler.unified;

import com.im.api.ApiRequest;
import com.im.api.RequestHandler;
import com.im.common.exception.UnauthorizedException;
import com.im.common.exception.ValidationException;
import com.im.core.call.GroupCallJoinResult;
import com.im.core.call.GroupCallManager;
import com.im.core.call.GroupCallSession;
import com.im.api.SignalingAction;
import com.im.api.content.SignalingContent;
import com.im.core.usecase.SendMessageUseCase;

import java.util.LinkedHashMap;
import java.util.Map;

public class GroupCallHandler implements RequestHandler {

    private final GroupCallManager groupCallManager;
    private final SendMessageUseCase sendMessageUseCase;

    public GroupCallHandler(GroupCallManager groupCallManager) {
        this(groupCallManager, null);
    }

    public GroupCallHandler(GroupCallManager groupCallManager, SendMessageUseCase sendMessageUseCase) {
        this.groupCallManager = groupCallManager;
        this.sendMessageUseCase = sendMessageUseCase;
    }

    @Override
    public Object handle(ApiRequest req) {
        String userId = req.currentUserId();
        if (userId == null) throw new UnauthorizedException("not authenticated");
        String groupId = req.getString("groupId");
        if (groupId == null || groupId.isBlank()) throw new ValidationException("groupId is required");

        return switch (req.operation()) {
            case "group.call.start" -> {
                GroupCallSession session = groupCallManager.start(userId, groupId, req.getString("callType", "video"));
                publishGroupSignal(req, userId, groupId, SignalingAction.CALLING, session);
                yield toMap(session);
            }
            case "group.call.join" -> toMap(groupCallManager.join(userId, groupId));
            case "group.call.leave" -> toMap(groupCallManager.leave(userId, groupId));
            case "group.call.end" -> {
                GroupCallSession session = groupCallManager.end(userId, groupId);
                publishGroupSignal(req, userId, groupId, SignalingAction.HANGUP, session);
                yield toMap(session);
            }
            case "group.call.active" -> toMap(groupCallManager.active(userId, groupId));
            default -> throw new ValidationException("unsupported group call operation: " + req.operation());
        };
    }

    private void publishGroupSignal(ApiRequest req, String userId, String groupId,
                                    SignalingAction action, GroupCallSession session) {
        if (sendMessageUseCase == null || session == null) return;
        // Group calls are discovered through the normal group message stream so clients
        // that reconnect or sync history see the same state transition as online clients.
        SignalingContent content = new SignalingContent(
                action, session.callType(), session.roomId(), null, null, null, 0);
        sendMessageUseCase.execute(req.params(), userId, null, groupId, content);
    }

    private static Map<String, Object> toMap(GroupCallJoinResult result) {
        Map<String, Object> body = toMap(result.session());
        body.put("token", result.token());
        body.put("sfuEndpoint", result.sfuEndpoint());
        return body;
    }

    private static Map<String, Object> toMap(GroupCallSession session) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (session == null) {
            body.put("active", false);
            return body;
        }
        body.put("active", !session.ended());
        body.put("ended", session.ended());
        body.put("groupId", session.groupId());
        body.put("roomId", session.roomId());
        body.put("callType", session.callType());
        body.put("initiatorUserId", session.initiatorUserId());
        body.put("sfuEndpoint", session.sfuEndpoint());
        body.put("startedAt", session.startedAt());
        body.put("participantCount", session.participantCount());
        return body;
    }
}
