package com.im.core.delivery;

import com.im.api.ConversationIds;
import com.im.api.IConversationManager;
import com.im.api.IGroupManager;
import com.im.api.IGroupMessageStore;
import com.im.api.ISingleMessageStore;
import com.im.api.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

/**
 * Persists a business message and updates the affected conversation views.
 */
final class MessagePersistenceWorkflow {

    private static final Logger log = LoggerFactory.getLogger(MessagePersistenceWorkflow.class);

    private final ISingleMessageStore singleMessageStore;
    private final IGroupMessageStore groupMessageStore;
    private final IConversationManager conversationManager;
    private final IGroupManager groupManager;

    MessagePersistenceWorkflow(ISingleMessageStore singleMessageStore,
                               IGroupMessageStore groupMessageStore,
                               IConversationManager conversationManager,
                               IGroupManager groupManager) {
        this.singleMessageStore = singleMessageStore;
        this.groupMessageStore = groupMessageStore;
        this.conversationManager = conversationManager;
        this.groupManager = groupManager;
    }

    void persist(Message msg) {
        String messageId = msg.getMessageId();
        String fromUserId = msg.getFromUserId();

        tryPersist(msg, messageId);
        updateConversations(msg, fromUserId);

        log.debug("Persisted msg {} (conv updated)", messageId);
    }

    private void tryPersist(Message msg, String messageId) {
        try {
            String groupId = msg.getGroupId();
            if (groupId != null && !groupId.isBlank()) {
                if (groupMessageStore != null) {
                    groupMessageStore.saveGroupMessage(msg);
                }
            } else if (singleMessageStore != null) {
                singleMessageStore.saveSingleMessage(msg);
            }
        } catch (Exception e) {
            if (isDuplicateEntry(e)) {
                log.debug("Msg already saved (dup), seqId={}, mid={}", msg.getMessageSeq(), messageId);
            } else {
                log.warn("Persistence save failed: seqId={}, err={}", msg.getMessageSeq(), e.getMessage());
                throw e;
            }
        }
    }

    private void updateConversations(Message msg, String fromUserId) {
        if (conversationManager == null) {
            return;
        }
        String groupId = msg.getGroupId();
        String toUserId = msg.getToUserId();

        if (groupId != null) {
            updateGroupConversations(msg, fromUserId, groupId);
        } else if (toUserId != null) {
            updateSingleConversations(msg, fromUserId, toUserId);
        }
    }

    private void updateGroupConversations(Message msg, String fromUserId, String groupId) {
        String conversationId = ConversationIds.group(groupId);

        if (fromUserId != null) {
            conversationManager.updateOnMessage(fromUserId, conversationId, msg, true);
        }

        Set<String> memberIds = groupManager != null
                ? groupManager.getMemberIds(groupId)
                : Set.of();
        for (String memberId : memberIds) {
            if (!memberId.equals(fromUserId)) {
                conversationManager.updateOnMessage(memberId, conversationId, msg, false);
            }
        }

        log.debug("Group conv updated for {} members: groupId={}", memberIds.size(), groupId);
    }

    private void updateSingleConversations(Message msg, String fromUserId, String toUserId) {
        String conversationId = ConversationIds.single(fromUserId, toUserId);

        if (fromUserId != null) {
            conversationManager.updateOnMessage(fromUserId, conversationId, msg, true);
        }
        conversationManager.updateOnMessage(toUserId, conversationId, msg, false);
    }

    private boolean isDuplicateEntry(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            String message = t.getMessage();
            if (message != null && message.contains("Duplicate entry")) {
                return true;
            }
        }
        return false;
    }
}
