package com.im.core.store;

import com.im.api.ConversationIds;
import com.im.api.IGroupMessageStore;
import com.im.api.IMessageStore;
import com.im.api.Message;
import com.im.common.exception.ValidationException;

import java.util.List;
import java.util.Objects;

/**
 * Group-chat storage adapter backed by the current unified message store.
 */
public class GroupMessageStoreAdapter implements IGroupMessageStore {

    private final IMessageStore delegate;

    public GroupMessageStoreAdapter(IMessageStore delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public void saveGroupMessage(Message message) {
        requireGroupMessage(message);
        delegate.save(message);
    }

    @Override
    public List<Message> pullGroupHistory(String groupId, long startSeq, long endSeq, int limit) {
        String conversationId = ConversationIds.group(groupId);
        if (conversationId == null) {
            throw new ValidationException("groupId is required");
        }
        return delegate.pullBySequence(conversationId, startSeq, endSeq, limit);
    }

    @Override
    public boolean revokeGroupMessage(String groupId, long seq, String revokerId, int role, String nickname) {
        String conversationId = ConversationIds.group(groupId);
        if (conversationId == null) {
            throw new ValidationException("groupId is required");
        }
        return delegate.revokeMessage(conversationId, seq, revokerId, role, nickname);
    }

    private static void requireGroupMessage(Message message) {
        if (message == null) {
            throw new ValidationException("message is required");
        }
        if (message.getGroupId() == null || message.getGroupId().isBlank()) {
            throw new ValidationException("group message requires groupId");
        }
    }
}
