package com.im.core.handler.unified;

import com.im.api.ApiRequest;
import com.im.api.Operation;
import com.im.api.RequestPreconditions;
import com.im.api.RequestHandler;
import com.im.common.exception.ValidationException;
import com.im.common.validation.Preconditions;
import com.im.core.call.GroupCallJoinResult;
import com.im.core.call.GroupCallManager;
import com.im.core.call.GroupCallSession;
import com.im.api.SignalingAction;
import com.im.api.content.SignalingContent;
import com.im.core.usecase.SendMessageUseCase;
import com.im.common.id.IdGenerator;

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
        String userId = RequestPreconditions.requireUser(req);
        String groupId = req.getString("groupId");
        groupId = Preconditions.requireText(groupId, "groupId");

        Operation operation = Operation.fromOpName(req.operation());
        if (operation == null) throw new ValidationException("unsupported group call operation: " + req.operation());
        return switch (operation) {
            case GROUP_CALL_START -> {
                GroupCallSession session = groupCallManager.start(userId, groupId, req.getString("callType", "video"));
                publishGroupSignal(req, userId, groupId, SignalingAction.CALLING, session);
                yield toMap(session);
            }
            case GROUP_CALL_JOIN -> {
                GroupCallJoinResult result = groupCallManager.join(userId, groupId);
                publishGroupSignal(req, userId, groupId, SignalingAction.ACCEPT, result.session());
                yield toMap(result);
            }
            case GROUP_CALL_LEAVE -> {
                GroupCallSession session = groupCallManager.leave(userId, groupId);
                publishGroupSignal(req, userId, groupId,
                        session != null && session.ended() ? SignalingAction.HANGUP : SignalingAction.CANCEL,
                        session);
                yield toMap(session, groupId);
            }
            case GROUP_CALL_END -> {
                GroupCallSession session = groupCallManager.end(userId, groupId);
                publishGroupSignal(req, userId, groupId, SignalingAction.HANGUP, session);
                yield toMap(session, groupId);
            }
            case GROUP_CALL_ACTIVE -> toMap(groupCallManager.active(userId, groupId), groupId);
            default -> throw new ValidationException("unsupported group call operation: " + req.operation());
        };
    }

    private void publishGroupSignal(ApiRequest req, String userId, String groupId,
                                    SignalingAction action, GroupCallSession session) {
        if (sendMessageUseCase == null || session == null) return;
        // 群通话状态走普通群消息流，是为了让重连和离线同步的客户端看到同一套状态变化，
        // 不需要再为通话维护一条只服务在线用户的旁路通知链路。
        SignalingContent content = new SignalingContent(
                action, session.callType(), session.roomId(), null, null, null, 0);
        Map<String, Object> params = new LinkedHashMap<>(req.params());
        params.putIfAbsent("clientMsgId", IdGenerator.messageId());
        sendMessageUseCase.execute(params, userId, null, groupId, content);
    }

    private static Map<String, Object> toMap(GroupCallJoinResult result) {
        Map<String, Object> body = toMap(result.session(), null);
        body.put("token", result.token());
        body.put("sfuEndpoint", result.sfuEndpoint());
        return body;
    }

    private static Map<String, Object> toMap(GroupCallSession session) {
        return toMap(session, null);
    }

    private static Map<String, Object> toMap(GroupCallSession session, String fallbackGroupId) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (session == null) {
            body.put("active", false);
            body.put("ended", true);
            if (fallbackGroupId != null) {
                body.put("groupId", fallbackGroupId);
            }
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
        body.put("updatedAt", session.updatedAt());
        body.put("participantCount", session.participantCount());
        body.put("participants", session.participants().stream()
                .map(participant -> Map.of("userId", participant.userId(), "joinedAt", participant.joinedAt()))
                .toList());
        return body;
    }
}
