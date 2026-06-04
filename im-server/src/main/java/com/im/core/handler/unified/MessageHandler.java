package com.im.core.handler.unified;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.im.api.ApiRequest;
import com.im.api.IConversationAccessChecker;
import com.im.api.IMessageStore;
import com.im.api.ISequenceManager;
import com.im.api.RequestHandler;
import com.im.api.SearchMessagesParam;
import com.im.api.SearchMessagesResult;
import com.im.common.exception.UnauthorizedException;
import com.im.common.exception.ValidationException;
import com.im.common.exception.NotFoundException;
import com.im.core.serialization.jackson.ObjectMapperProvider;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private final IConversationAccessChecker accessChecker;

    public MessageHandler(IMessageStore messageStore, ISequenceManager sequenceManager) {
        this(messageStore, sequenceManager, null);
    }

    public MessageHandler(IMessageStore messageStore, ISequenceManager sequenceManager,
                          IConversationAccessChecker accessChecker) {
        this.messageStore = messageStore;
        this.sequenceManager = sequenceManager;
        this.accessChecker = accessChecker;
    }

    @Override
    public Object handle(ApiRequest req) {
        return switch (req.operation()) {
            case "chat.pull" -> handlePull(req);
            case "chat.seq" -> handleSeq(req);
            case "chat.sync" -> handleSync(req);
            case "chat.search" -> handleSearch(req);
            default -> throw new NotFoundException("unsupported: " + req.operation());
        };
    }

    private Object handlePull(ApiRequest req) {
        String userId = req.currentUserId();
        if (userId == null) throw new UnauthorizedException("not authenticated");
        String conversationId = req.getString("conversationId");
        if (conversationId == null) throw new ValidationException("conversationId is required");
        requireReadable(userId, conversationId);
        long startSeq = req.getInt("startSeq", 0);
        long endSeq = req.getInt("endSeq", 0);
        int limit = req.getInt("limit", 50);

        var messages = messageStore.pullBySequence(conversationId, startSeq, endSeq, limit);
        long maxSeq = sequenceManager.getMaximumSequence(conversationId);
        return Map.of("conversationId", conversationId, "messages", toMapList(messages),
                "count", messages.size(), "maxSeq", maxSeq);
    }

    private Object handleSeq(ApiRequest req) {
        String userId = req.currentUserId();
        if (userId == null) throw new UnauthorizedException("not authenticated");
        String conversationId = req.getString("conversationId");
        if (conversationId == null) throw new ValidationException("conversationId is required");
        requireReadable(userId, conversationId);
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
        String userId = req.currentUserId();
        if (userId == null) throw new UnauthorizedException("not authenticated");
        Map<String, Object> seqsRaw = (Map<String, Object>) req.params().get("seqs");
        int limit = req.getInt("limit", 50);

        if (seqsRaw == null || seqsRaw.isEmpty()) {
            return Map.of("syncs", List.of());
        }

        List<Map<String, Object>> syncs = new ArrayList<>(seqsRaw.size());
        for (Map.Entry<String, Object> entry : seqsRaw.entrySet()) {
            String convId = entry.getKey();
            requireReadable(userId, convId);
            long lastSeq = entry.getValue() instanceof Number
                    ? ((Number) entry.getValue()).longValue() : 0;

            // 拉取 lastSeq 之后的新消息
            var messages = messageStore.pullBySequence(convId, lastSeq + 1, 0, limit);
            long maxSeq = sequenceManager.getMaximumSequence(convId);
            syncs.add(Map.of(
                    "conversationId", convId,
                    "messages", toMapList(messages),
                    "maxSeq", maxSeq
            ));
        }

        return Map.of("syncs", syncs);
    }

    @SuppressWarnings("unchecked")
    private Object handleSearch(ApiRequest req) {
        String userId = req.currentUserId();
        if (userId == null) throw new UnauthorizedException("not authenticated");

        String keyword = req.getString("keyword");
        List<String> contentTypeFilter = (List<String>) req.params().get("contentTypeFilter");
        List<String> conversationIds = readableSearchConversationIds(userId,
                (List<String>) req.params().get("conversationIds"));
        if (conversationIds != null && conversationIds.isEmpty()) {
            return Map.of(
                    "messages", List.of(),
                    "totalCount", 0,
                    "hasMore", false
            );
        }
        Number startTimeVal = (Number) req.params().get("startTime");
        Long startTime = startTimeVal != null ? startTimeVal.longValue() : null;
        Number endTimeVal = (Number) req.params().get("endTime");
        Long endTime = endTimeVal != null ? endTimeVal.longValue() : null;
        String senderId = req.getString("senderId");
        int limit = req.getInt("limit", 20);
        int offset = req.getInt("offset", 0);

        SearchMessagesParam param = SearchMessagesParam.builder()
                .userId(userId)
                .keyword(keyword)
                .contentTypeFilter(contentTypeFilter)
                .conversationIds(conversationIds)
                .startTime(startTime)
                .endTime(endTime)
                .senderId(senderId)
                .limit(limit)
                .offset(offset)
                .build();

        SearchMessagesResult result = messageStore.searchMessages(param);

        return Map.of(
                "messages", toMapList(result.getMessages()),
                "totalCount", result.getTotalCount(),
                "hasMore", result.hasMore()
        );
    }

    private void requireReadable(String userId, String conversationId) {
        if (accessChecker != null) {
            accessChecker.requireReadable(userId, conversationId);
        }
    }

    private List<String> readableSearchConversationIds(String userId, List<String> requestedConversationIds) {
        if (accessChecker == null) {
            return requestedConversationIds;
        }
        List<String> readable = accessChecker.listReadableConversationIds(userId);
        if (readable == null || readable.isEmpty()) {
            return List.of();
        }
        if (requestedConversationIds == null || requestedConversationIds.isEmpty()) {
            return readable;
        }
        Set<String> readableSet = new HashSet<>(readable);
        return requestedConversationIds.stream()
                .filter(readableSet::contains)
                .distinct()
                .toList();
    }

    // 抽取公共方法而非三次重复 lambda，统一处理序列化异常
    @SuppressWarnings("rawtypes")
    private List<Map> toMapList(List<?> items) {
        return items.stream().map(item -> {
            try {
                return MAPPER.convertValue(item, Map.class);
            } catch (Exception e) {
                return Map.of("error", "serialization failed");
            }
        }).collect(Collectors.toList());
    }
}
