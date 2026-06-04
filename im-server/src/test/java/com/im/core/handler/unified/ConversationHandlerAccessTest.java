package com.im.core.handler.unified;

import com.im.api.ApiRequest;
import com.im.api.Conversation;
import com.im.api.IConversationAccessChecker;
import com.im.api.IConversationManager;
import com.im.api.IncrementalSyncResult;
import com.im.api.Message;
import com.im.api.Operation;
import com.im.common.enums.ImErrorCode;
import com.im.common.exception.ImException;
import com.im.common.exception.UnauthorizedException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConversationHandlerAccessTest {

    @Test
    void markReadWithoutAuthenticatedUserThrowsUnauthorized() {
        ConversationHandler handler = new ConversationHandler(new RecordingConversationManager(), new RecordingAccessChecker());
        ApiRequest request = new ApiRequest(Operation.CONVERSATION_READ,
                Map.of("conversationId", "single_alice_bob", "readSeq", 10), Map.of(), null, null);

        assertThrows(UnauthorizedException.class, () -> handler.handle(request));
    }

    @Test
    void markReadRejectsConversationCurrentUserCannotRead() {
        RecordingAccessChecker accessChecker = new RecordingAccessChecker();
        RecordingConversationManager manager = new RecordingConversationManager();
        ConversationHandler handler = new ConversationHandler(manager, accessChecker);
        ApiRequest request = request(Operation.CONVERSATION_READ,
                Map.of("conversationId", "single_alice_bob", "readSeq", 10), "mallory");

        ImException ex = assertThrows(ImException.class, () -> handler.handle(request));

        assertEquals(ImErrorCode.FORBIDDEN, ex.getErrorCode());
        assertEquals(List.of("mallory|single_alice_bob"), accessChecker.checkedReads);
        assertEquals(0, manager.markReadCalls);
    }

    @Test
    void markReadAllowsReadableConversationAndKeepsHighestReadSeq() {
        RecordingAccessChecker accessChecker = new RecordingAccessChecker();
        accessChecker.readableConversationIds.add("single_alice_bob");
        RecordingConversationManager manager = new RecordingConversationManager();
        manager.readSeq = 20;
        ConversationHandler handler = new ConversationHandler(manager, accessChecker);
        ApiRequest request = request(Operation.CONVERSATION_READ,
                Map.of("conversationId", "single_alice_bob", "readSeq", 10), "alice");

        handler.handle(request);

        assertEquals(1, manager.markReadCalls);
        assertEquals(20, manager.lastReadSeq);
    }

    private static ApiRequest request(Operation operation, Map<String, Object> params, String userId) {
        ApiRequest request = new ApiRequest(operation, params, Map.of(), null, null);
        request.setAttribute("_uid", userId);
        return request;
    }

    private static final class RecordingAccessChecker implements IConversationAccessChecker {
        private final List<String> checkedReads = new ArrayList<>();
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
            return List.copyOf(readableConversationIds);
        }
    }

    private static final class RecordingConversationManager implements IConversationManager {
        private int markReadCalls;
        private long readSeq;
        private long lastReadSeq;

        @Override public List<Conversation> getConversations(String ownerUserId) { return List.of(); }
        @Override public Conversation getConversation(String ownerUserId, String conversationId) { return null; }
        @Override public void updateOnMessage(String ownerUserId, String conversationId, Message msg, boolean isSelf) {}

        @Override
        public void markRead(String ownerUserId, String conversationId, long readSeq) {
            markReadCalls++;
            lastReadSeq = Math.max(this.readSeq, readSeq);
            this.readSeq = lastReadSeq;
        }

        @Override public void setPinned(String ownerUserId, String conversationId, boolean pinned) {}
        @Override public void setRecvMsgOpt(String ownerUserId, String conversationId, int recvMsgOpt) {}
        @Override public void setBurnDuration(String ownerUserId, String conversationId, int burnDuration) {}
        @Override public long getReadSeq(String ownerUserId, String conversationId) { return readSeq; }
        @Override public int getTotalUnreadCount(String userId) { return 0; }
        @Override public int getUnreadCount(String ownerUserId, String conversationId) { return 0; }
        @Override public IncrementalSyncResult<Conversation> getIncrementalConversations(String ownerUserId, long version) {
            return new IncrementalSyncResult<>(List.of(), version, false);
        }
    }
}
