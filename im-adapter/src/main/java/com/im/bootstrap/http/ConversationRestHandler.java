package com.im.bootstrap.http;

import com.im.api.Conversation;
import com.im.api.IConversationManager;
import com.im.api.ImErrorCode;
import com.im.api.ImException;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;

import java.util.List;
import java.util.Map;

import static com.im.bootstrap.http.HttpParamUtils.*;

/**
 * 会话域 REST 控制器。
 *
 * <p>处理 /api/conversation/* 路由：查询会话列表、更新会话设置。</p>
 */
public class ConversationRestHandler implements RestController {

    private final IConversationManager conversationManager;

    public ConversationRestHandler(IConversationManager conversationManager) {
        this.conversationManager = conversationManager;
    }

    @Override
    public void register(HttpRestHandler router) {
        router.get("/api/conversation/list", this::handleList);
        router.post("/api/conversation/set", this::handleSet);
    }

    private Object handleList(FullHttpRequest req, ChannelHandlerContext ctx) {
        Map<String, String> params = parseQuery(req);
        String userId = params.get("userId");
        if (userId == null) throw new ImException(ImErrorCode.BAD_REQUEST, "userId is required");
        List<Conversation> conversations = conversationManager.getConversations(userId);
        return Map.of("userId", userId, "conversations", conversations, "count", conversations.size());
    }

    private Object handleSet(FullHttpRequest req, ChannelHandlerContext ctx) {
        Map<String, Object> body = parseJsonBody(req);
        String userId = str(body, "userId");
        String conversationId = str(body, "conversationId");
        if (userId == null || conversationId == null) {
            throw new ImException(ImErrorCode.BAD_REQUEST, "userId and conversationId are required");
        }
        if (body.containsKey("pinned")) {
            conversationManager.setPinned(userId, conversationId, bool(body, "pinned", false));
        }
        if (body.containsKey("recvMsgOpt")) {
            conversationManager.setRecvMsgOpt(userId, conversationId, intObj(body, "recvMsgOpt", 0));
        }
        return Map.of("conversationId", conversationId, "status", "OK");
    }
}
