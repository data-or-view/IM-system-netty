package com.im.core.store;

import com.im.api.ConversationIds;
import com.im.api.IMessageStore;
import com.im.api.ISingleMessageStore;
import com.im.api.Message;
import com.im.common.exception.ValidationException;

import java.util.List;
import java.util.Objects;

/**
 * Single-chat storage adapter backed by the current unified message store.
 */
public class SingleMessageStoreAdapter implements ISingleMessageStore {

    private final IMessageStore delegate;

    public SingleMessageStoreAdapter(IMessageStore delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public void saveSingleMessage(Message message) {
        requireSingleMessage(message);
        delegate.save(message);
    }

    @Override
    public List<Message> pullSingleHistory(String userA, String userB, long startSeq, long endSeq, int limit) {
        String conversationId = ConversationIds.single(userA, userB);
        if (conversationId == null) {
            throw new ValidationException("single chat participants are required");
        }
        return delegate.pullBySequence(conversationId, startSeq, endSeq, limit);
    }

    @Override
    public boolean revokeSingleMessage(String conversationId, long seq, String revokerId, String nickname) {
        if (conversationId == null || !conversationId.startsWith("single_")) {
            throw new ValidationException("single conversationId is required");
        }
        return delegate.revokeMessage(conversationId, seq, revokerId, 0, nickname);
    }

    private static void requireSingleMessage(Message message) {
        if (message == null) {
            throw new ValidationException("message is required");
        }
        if (message.getGroupId() != null && !message.getGroupId().isBlank()) {
            throw new ValidationException("single message must not contain groupId");
        }
        if (message.getFromUserId() == null || message.getFromUserId().isBlank()
                || message.getToUserId() == null || message.getToUserId().isBlank()) {
            throw new ValidationException("single message requires fromUserId and toUserId");
        }
    }
}
