package com.im.core.handler.unified;

import com.im.api.ApiRequest;
import com.im.api.RequestPreconditions;
import com.im.api.RequestHandler;
import com.im.common.exception.ValidationException;
import com.im.core.usecase.RevokeResult;
import com.im.core.usecase.RevokeUseCase;

import java.util.Map;
import java.util.function.Consumer;

/**
 * 消息撤回 handler。
 *
 * <p>接收 {@code {"op":"msg_revoke","conversationId":"xxx","messageSeq":123,"groupId":"xxx"}} 请求，
 * 调用 {@link RevokeUseCase} 执行撤回，并向在线接收方推送撤回通知。</p>
 */
public class RevokeHandler implements RequestHandler {

    private final RevokeUseCase revokeUseCase;
    private final Consumer<RevokeResult> revokeNotifier;

    public RevokeHandler(RevokeUseCase revokeUseCase, Consumer<RevokeResult> revokeNotifier) {
        this.revokeUseCase = revokeUseCase;
        this.revokeNotifier = revokeNotifier;
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
        if (revokeNotifier != null) {
            revokeNotifier.accept(result);
        }

        return Map.of(
                "conversationId", conversationId,
                "messageSeq", seq,
                "status", "REVOKED"
        );
    }
}
