package com.im.bootstrap.http;

import com.im.api.*;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.im.bootstrap.http.HttpParamUtils.*;

/**
 * 消息域 REST 控制器。
 *
 * <p>处理 /api/msg/* 路由：拉取历史消息、查询序列号。</p>
 */
public class MessageRestHandler implements RestController {

    private final IMessageStore messageStore;
    private final ISequenceManager sequenceManager;

    public MessageRestHandler(IMessageStore messageStore, ISequenceManager sequenceManager) {
        this.messageStore = messageStore;
        this.sequenceManager = sequenceManager;
    }

    @Override
    public void register(HttpRestHandler router) {
        router.post("/api/msg/pull", this::handlePull);
        router.get("/api/msg/seq", this::handleSeq);
    }

    private Object handlePull(FullHttpRequest req, ChannelHandlerContext ctx) {
        Map<String, Object> body = parseJsonBody(req);
        String conversationId = str(body, "conversationId");
        if (conversationId == null) throw new ImException(ImErrorCode.BAD_REQUEST, "conversationId is required");
        long startSeq = longObj(body, "startSeq", 0);
        long endSeq = longObj(body, "endSeq", 0);
        int limit = intObj(body, "limit", 50);
        List<IMCommand> messages = messageStore.pullBySequence(conversationId, startSeq, endSeq, limit);
        long maxSeq = sequenceManager.getMaximumSequence(conversationId);
        return Map.of("conversationId", conversationId, "messages",
                messages.stream().map(IMCommand::toJsonMap).collect(Collectors.toList()),
                "count", messages.size(), "maxSeq", maxSeq);
    }

    private Object handleSeq(FullHttpRequest req, ChannelHandlerContext ctx) {
        Map<String, String> params = parseQuery(req);
        String conversationId = params.get("conversationId");
        if (conversationId == null) throw new ImException(ImErrorCode.BAD_REQUEST, "conversationId is required");
        return Map.of("conversationId", conversationId, "maxSeq",
                sequenceManager.getMaximumSequence(conversationId));
    }
}
