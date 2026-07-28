package com.im.core.handler.unified;

import com.im.api.ApiRequest;
import com.im.api.IConversationAccessChecker;
import com.im.api.IMessageStore;
import com.im.api.ISequenceManager;
import com.im.api.Message;
import com.im.api.Operation;
import com.im.api.SearchMessagesParam;
import com.im.api.SearchMessagesResult;
import com.im.common.enums.ImErrorCode;
import com.im.common.exception.ImException;
import com.im.common.exception.ValidationException;
import com.im.core.db.mapper.MessageMapper;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessageHandlerAccessTest {

    @Test
    void pullRejectsConversationCurrentUserCannotRead() {
        RecordingAccessChecker accessChecker = new RecordingAccessChecker();
        MessageHandler handler = new MessageHandler(new RecordingMessageStore(), new FixedSequenceManager(), accessChecker);
        ApiRequest request = request(Operation.CHAT_PULL, Map.of("conversationId", "single_alice_bob"), "mallory");

        ImException ex = assertThrows(ImException.class, () -> handler.handle(request));

        assertEquals(ImErrorCode.FORBIDDEN, ex.getErrorCode());
        assertEquals(List.of("mallory|single_alice_bob"), accessChecker.checkedReads);
    }

    @Test
    void syncRejectsAnyConversationCurrentUserCannotRead() {
        RecordingAccessChecker accessChecker = new RecordingAccessChecker();
        MessageHandler handler = new MessageHandler(new RecordingMessageStore(), new FixedSequenceManager(), accessChecker);
        ApiRequest request = request(Operation.CHAT_SYNC,
                Map.of("seqs", Map.of("single_alice_bob", 10)), "mallory");

        ImException ex = assertThrows(ImException.class, () -> handler.handle(request));

        assertEquals(ImErrorCode.FORBIDDEN, ex.getErrorCode());
        assertEquals(List.of("mallory|single_alice_bob"), accessChecker.checkedReads);
    }

    @SuppressWarnings("unchecked")
    @Test
    void searchUsesOnlyReadableConversationIds() {
        RecordingAccessChecker accessChecker = new RecordingAccessChecker();
        accessChecker.readableConversationIds.add("single_alice_bob");
        RecordingMessageStore store = new RecordingMessageStore();
        MessageHandler handler = new MessageHandler(store, new FixedSequenceManager(), accessChecker);
        ApiRequest request = request(Operation.CHAT_SEARCH,
                Map.of("conversationIds", List.of("single_alice_bob", "single_alice_mallory"), "keyword", "hello"),
                "alice");

        Map<String, Object> response = (Map<String, Object>) handler.handle(request);

        assertEquals(0, response.get("totalCount"));
        assertEquals(List.of("single_alice_bob"), store.lastSearchParam.getConversationIds());
        assertEquals(List.of("alice"), accessChecker.listReadableCalls);
    }

    @SuppressWarnings("unchecked")
    @Test
    void searchReturnsEmptyWhenRequestedConversationsAreNotReadable() {
        RecordingAccessChecker accessChecker = new RecordingAccessChecker();
        accessChecker.readableConversationIds.add("single_alice_bob");
        RecordingMessageStore store = new RecordingMessageStore();
        MessageHandler handler = new MessageHandler(store, new FixedSequenceManager(), accessChecker);
        ApiRequest request = request(Operation.CHAT_SEARCH,
                Map.of("conversationIds", List.of("single_alice_mallory"), "keyword", "hello"),
                "alice");

        Map<String, Object> response = (Map<String, Object>) handler.handle(request);

        assertEquals(0, response.get("totalCount"));
        assertTrue(((List<?>) response.get("messages")).isEmpty());
        assertEquals(0, store.searchCalls);
    }

    @Test
    void searchWithoutAccessCheckerAndConversationFilterDoesNotFail() {
        RecordingMessageStore store = new RecordingMessageStore();
        MessageHandler handler = new MessageHandler(store, new FixedSequenceManager(), null);
        ApiRequest request = request(Operation.CHAT_SEARCH, Map.of("keyword", "hello"), "alice");

        handler.handle(request);

        assertEquals(1, store.searchCalls);
    }

    @Test
    void pullClampsRequestedLimitBeforeStoreInvocation() {
        RecordingMessageStore store = new RecordingMessageStore();
        MessageHandler handler = new MessageHandler(store, new FixedSequenceManager());

        handler.handle(request(Operation.CHAT_PULL,
                Map.of("conversationId", "single_alice_bob", "limit", 1), "alice"));
        assertEquals(1, store.lastPullLimit);

        handler.handle(request(Operation.CHAT_PULL,
                Map.of("conversationId", "single_alice_bob", "limit", 100), "alice"));
        assertEquals(100, store.lastPullLimit);

        handler.handle(request(Operation.CHAT_PULL,
                Map.of("conversationId", "single_alice_bob", "limit", 101), "alice"));
        assertEquals(100, store.lastPullLimit);
        assertEquals(Long.MAX_VALUE, store.lastPullEndSeq);
    }

    @Test
    void syncRejectsMoreThanDefaultConversationLimit() {
        RecordingMessageStore store = new RecordingMessageStore();
        MessageHandler handler = new MessageHandler(store, new FixedSequenceManager());

        handler.handle(request(Operation.CHAT_SYNC, Map.of("seqs", syncSeqs(20)), "alice"));
        assertEquals(20, store.pullCalls);
        assertEquals(Long.MAX_VALUE, store.lastPullEndSeq);

        assertThrows(ValidationException.class,
                () -> handler.handle(request(Operation.CHAT_SYNC, Map.of("seqs", syncSeqs(21)), "alice")));
    }

    @Test
    void syncRejectsMoreThanConfiguredConversationLimit() {
        RecordingMessageStore store = new RecordingMessageStore();
        MessageHandler handler = new MessageHandler(store, new FixedSequenceManager(), null,
                new MessageQueryLimits(100, 2));

        assertThrows(ValidationException.class,
                () -> handler.handle(request(Operation.CHAT_SYNC, Map.of("seqs", syncSeqs(3)), "alice")));
        assertEquals(0, store.pullCalls);
    }

    @Test
    void sequenceRangeMapperHasDatabaseLimitParameter() throws NoSuchMethodException {
        Select select = MessageMapper.class
                .getMethod("selectBySeqRange", String.class, long.class, long.class, int.class)
                .getAnnotation(Select.class);

        assertTrue(select.value()[0].contains("LIMIT #{limit}"));
    }

    @Test
    void queryLimitsUseConfiguredBounds() {
        MessageQueryLimits limits = MessageQueryLimits.from(new TestConfig(Map.of(
                "im.message.pull.max-limit", "7",
                "im.message.sync.max-conversations", "3"
        )));

        assertEquals(7, limits.maxPullLimit());
        assertEquals(3, limits.maxSyncConversations());
        assertEquals(7, limits.clampPullLimit(8));
    }

    private static Map<String, Object> syncSeqs(int count) {
        Map<String, Object> seqs = new java.util.LinkedHashMap<>();
        for (int index = 0; index < count; index++) {
            seqs.put("conversation-" + index, 0L);
        }
        return seqs;
    }

    private static ApiRequest request(Operation operation, Map<String, Object> params, String userId) {
        ApiRequest request = new ApiRequest(operation, params, Map.of(), null, null);
        request.setAttribute("_uid", userId);
        return request;
    }

    private static final class RecordingAccessChecker implements IConversationAccessChecker {
        private final List<String> checkedReads = new ArrayList<>();
        private final List<String> listReadableCalls = new ArrayList<>();
        private final Set<String> readableConversationIds = new HashSet<>();

        @Override
        public void requireReadable(String userId, String conversationId) {
            checkedReads.add(userId + "|" + conversationId);
            if (!readableConversationIds.contains(conversationId)) {
                throw new ImException(ImErrorCode.FORBIDDEN, "conversation not readable");
            }
        }

        @Override
        public List<String> listReadableConversationIds(String userId) {
            listReadableCalls.add(userId);
            return List.copyOf(readableConversationIds);
        }
    }

    private static final class RecordingMessageStore implements IMessageStore {
        private SearchMessagesParam lastSearchParam;
        private int searchCalls;
        private int lastPullLimit;
        private long lastPullEndSeq;
        private int pullCalls;

        @Override public void save(Message msg) {}
        @Override public List<Message> pullOffline(String userId, int limit) { return List.of(); }
        @Override
        public List<Message> pullBySequence(String conversationId, long startSeq, long endSeq, int limit) {
            lastPullLimit = limit;
            lastPullEndSeq = endSeq;
            pullCalls++;
            return List.of();
        }
        @Override public void markDelivered(String userId, List<String> msgIds) {}

        @Override
        public SearchMessagesResult searchMessages(SearchMessagesParam param) {
            searchCalls++;
            lastSearchParam = param;
            return SearchMessagesResult.empty();
        }
    }

    private static final class FixedSequenceManager implements ISequenceManager {
        @Override public long nextSequence(String conversationId) { return 1; }
        @Override public long getMaximumSequence(String conversationId) { return 42; }
    }

    private record TestConfig(Map<String, String> values) implements com.im.config.Config {
        @Override public java.util.Optional<String> getString(String key) { return java.util.Optional.ofNullable(values.get(key)); }
        @Override public java.util.Optional<Integer> getInt(String key) { return java.util.Optional.ofNullable(values.get(key)).map(Integer::parseInt); }
        @Override public java.util.Optional<Long> getLong(String key) { return java.util.Optional.ofNullable(values.get(key)).map(Long::parseLong); }
        @Override public java.util.Optional<Boolean> getBoolean(String key) { return java.util.Optional.ofNullable(values.get(key)).map(Boolean::parseBoolean); }
        @Override public java.util.Optional<java.time.Duration> getDuration(String key) { return java.util.Optional.empty(); }
        @Override public boolean hasKey(String key) { return values.containsKey(key); }
    }
}
