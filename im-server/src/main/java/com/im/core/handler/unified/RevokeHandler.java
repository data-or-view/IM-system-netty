package com.im.core.handler.unified;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.im.api.ApiRequest;
import com.im.api.IConnectionSession;
import com.im.api.ISessionManager;
import com.im.api.ProtocolFields;
import com.im.api.RequestPreconditions;
import com.im.api.RequestHandler;
import com.im.common.exception.ValidationException;
import com.im.core.serialization.jackson.ObjectMapperProvider;
import com.im.core.usecase.RevokeResult;
import com.im.core.usecase.RevokeUseCase;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 消息撤回 handler。
 *
 * <p>接收 {@code {"op":"msg_revoke","conversationId":"xxx","messageSeq":123,"groupId":"xxx"}} 请求，
 * 调用 {@link RevokeUseCase} 执行撤回，并向在线接收方推送撤回通知。</p>
 */
public class RevokeHandler implements RequestHandler {

    private static final ObjectMapper MAPPER = ObjectMapperProvider.get();

    private final RevokeUseCase revokeUseCase;
    private final ISessionManager sessionManager;

    public RevokeHandler(RevokeUseCase revokeUseCase, ISessionManager sessionManager) {
        this.revokeUseCase = revokeUseCase;
        this.sessionManager = sessionManager;
    }

    @Override
    public Object handle(ApiRequest req) {
        String userId = RequestPreconditions.requireUser(req);

        String conversationId = req.getString("conversationId");
        long seq = req.getLong("messageSeq", 0);
        String groupId = req.getString("groupId");

        if (conversationId == null || seq <= 0) {
            throw new ValidationException("conversationId and messageSeq are required");
        }

        RevokeResult result = revokeUseCase.execute(userId, conversationId, seq, groupId);

        // 推送撤回通知给在线接收方
        pushRevokeNotification(result);

        return Map.of(
                "conversationId", conversationId,
                "messageSeq", seq,
                "status", "REVOKED"
        );
    }

    private void pushRevokeNotification(RevokeResult result) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(ProtocolFields.CONVERSATION_ID, result.conversationId());
        data.put(ProtocolFields.SEQ, result.seq());
        data.put(ProtocolFields.REVOKER_ID, result.revokerId());

        Map<String, Object> notification = new LinkedHashMap<>();
        notification.put(ProtocolFields.OP, ProtocolFields.OP_MESSAGE_REVOKED);
        notification.put(ProtocolFields.CODE, 0);
        notification.put(ProtocolFields.DATA, data);

        String json;
        try {
            json = MAPPER.writeValueAsString(notification);
        } catch (Exception e) {
            return;
        }

        for (String targetUserId : result.targetUserIds()) {
            for (IConnectionSession session : sessionManager.getSessionsByUserId(targetUserId)) {
                if (session.getConnection().isActive()) {
                    session.getConnection().write(new TextWebSocketFrame(json));
                }
            }
        }
    }
}
