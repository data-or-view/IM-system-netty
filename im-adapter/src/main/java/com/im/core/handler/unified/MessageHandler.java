package com.im.core.handler.unified;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.im.api.ApiRequest;
import com.im.api.IMessageStore;
import com.im.api.ISequenceManager;
import com.im.api.RequestHandler;
import com.im.common.enums.ImErrorCode;
import com.im.common.exception.ImException;
import com.im.core.serialization.jackson.ObjectMapperProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 消息域 handler：拉取历史消息、查询最大序列号。
 *
 * <p>合并 WS {@code PullMessageHandler} + HTTP {@code MessageRestHandler}。</p>
 *
 * <p>注意：此 handler 处理的是"拉取消息"（chat.pull / chat.seq），
 * 不是"发送消息"（chat.send / chat.send.group，由 {@code ChatHandler} 处理）。</p>
 */
public class MessageHandler implements RequestHandler {

    private static final ObjectMapper MAPPER = ObjectMapperProvider.get();

    private final IMessageStore messageStore;
    private final ISequenceManager sequenceManager;

    public MessageHandler(IMessageStore messageStore, ISequenceManager sequenceManager) {
        this.messageStore = messageStore;
        this.sequenceManager = sequenceManager;
    }

    @Override
    public Object handle(ApiRequest req) {
        return switch (req.operation()) {
            case "chat.pull" -> handlePull(req);
            case "chat.seq" -> handleSeq(req);
            case "chat.sync" -> handleSync(req);
            default -> throw new ImException(ImErrorCode.NOT_FOUND, "unsupported: " + req.operation());
        };
    }

    private Object handlePull(ApiRequest req) {
        String conversationId = req.getString("conversationId");
        if (conversationId == null) throw new ImException(ImErrorCode.BAD_REQUEST, "conversationId is required");
        long startSeq = req.getInt("startSeq", 0);
        long endSeq = req.getInt("endSeq", 0);
        int limit = req.getInt("limit", 50);

        var messages = messageStore.pullBySequence(conversationId, startSeq, endSeq, limit);
        @SuppressWarnings("rawtypes")
        List<Map> msgMaps = messages.stream()
                .map(msg -> {
                    try {
                        return MAPPER.convertValue(msg, Map.class);
                    } catch (Exception e) {
                        return Map.of("error", "serialization failed");
                    }
                })
                .collect(Collectors.toList());
        long maxSeq = sequenceManager.getMaximumSequence(conversationId);
        return Map.of("conversationId", conversationId, "messages", msgMaps,
                "count", msgMaps.size(), "maxSeq", maxSeq);
    }

    private Object handleSeq(ApiRequest req) {
        String conversationId = req.getString("conversationId");
        if (conversationId == null) throw new ImException(ImErrorCode.BAD_REQUEST, "conversationId is required");
        long maxSeq = sequenceManager.getMaximumSequence(conversationId);
        return Map.of("conversationId", conversationId, "maxSeq", maxSeq);
    }

    /**
     * 增量消息同步。
     *
     * <p>客户端传入各会话的已知 seq（{@code seqs: {convId: lastKnownSeq}}），
     * 服务端返回每个会话中比已知 seq 更新的消息。</p>
     *
     * <p>典型使用场景：用户上线后拉取离线期间的消息。</p>
     */
    @SuppressWarnings("unchecked")
    private Object handleSync(ApiRequest req) {
        Map<String, Object> seqsRaw = (Map<String, Object>) req.params().get("seqs");
        int limit = req.getInt("limit", 50);

        if (seqsRaw == null || seqsRaw.isEmpty()) {
            return Map.of("syncs", List.of());
        }

        List<Map<String, Object>> syncs = new ArrayList<>(seqsRaw.size());
        for (Map.Entry<String, Object> entry : seqsRaw.entrySet()) {
            String convId = entry.getKey();
            long lastSeq = entry.getValue() instanceof Number
                    ? ((Number) entry.getValue()).longValue() : 0;

            // 拉取 lastSeq 之后的新消息
            var messages = messageStore.pullBySequence(convId, lastSeq + 1, 0, limit);
            @SuppressWarnings("rawtypes")
            List<Map> msgMaps = messages.stream()
                    .map(msg -> {
                        try {
                            return MAPPER.convertValue(msg, Map.class);
                        } catch (Exception e) {
                            return Map.of("error", "serialization failed");
                        }
                    })
                    .collect(Collectors.toList());

            long maxSeq = sequenceManager.getMaximumSequence(convId);
            syncs.add(Map.of(
                    "conversationId", convId,
                    "messages", msgMaps,
                    "maxSeq", maxSeq
            ));
        }

        return Map.of("syncs", syncs);
    }
}
