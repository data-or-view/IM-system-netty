package com.im.core.conversation;

import com.im.api.Conversation;
import com.im.api.IConversationManager;
import com.im.api.IncrementalSyncResult;
import com.im.api.Message;
import com.im.core.cache.Cache;

import java.util.List;
import java.util.Optional;

/**
 * Redis-backed conversation cache decorator.
 *
 * <p>Conversation lists are read-heavy and rebuilt from multiple DB queries because
 * unread count is derived from read state. This decorator caches both the user's
 * ordered list and individual conversation views, then invalidates them immediately
 * after every write path that can change list ordering, unread count, or settings.</p>
 */
public class CachedConversationManager implements IConversationManager {

    private final IConversationManager delegate;
    private final Cache<String, ConversationListSnapshot> listCache;
    private final Cache<String, Conversation> conversationCache;

    public CachedConversationManager(IConversationManager delegate,
                                     Cache<String, ConversationListSnapshot> listCache,
                                     Cache<String, Conversation> conversationCache) {
        this.delegate = delegate;
        this.listCache = listCache;
        this.conversationCache = conversationCache;
    }

    @Override
    public List<Conversation> getConversations(String ownerUserId) {
        Optional<ConversationListSnapshot> cached = listCache.get(ownerUserId);
        if (cached.isPresent()) {
            return cached.get().getConversations();
        }
        List<Conversation> loaded = delegate.getConversations(ownerUserId);
        listCache.put(ownerUserId, new ConversationListSnapshot(loaded));
        for (Conversation conversation : loaded) {
            conversationCache.put(conversationKey(ownerUserId, conversation.getConversationId()), conversation);
        }
        return loaded;
    }

    @Override
    public Conversation getConversation(String ownerUserId, String conversationId) {
        String key = conversationKey(ownerUserId, conversationId);
        Optional<Conversation> cached = conversationCache.get(key);
        if (cached.isPresent()) {
            return cached.get();
        }
        Conversation loaded = delegate.getConversation(ownerUserId, conversationId);
        if (loaded != null) {
            conversationCache.put(key, loaded);
        }
        return loaded;
    }

    @Override
    public void updateOnMessage(String ownerUserId, String conversationId, Message msg, boolean isSelf) {
        delegate.updateOnMessage(ownerUserId, conversationId, msg, isSelf);
        invalidateConversation(ownerUserId, conversationId);
    }

    @Override
    public void markRead(String ownerUserId, String conversationId, long readSeq) {
        delegate.markRead(ownerUserId, conversationId, readSeq);
        invalidateConversation(ownerUserId, conversationId);
    }

    @Override
    public void setPinned(String ownerUserId, String conversationId, boolean pinned) {
        delegate.setPinned(ownerUserId, conversationId, pinned);
        invalidateConversation(ownerUserId, conversationId);
    }

    @Override
    public void setRecvMsgOpt(String ownerUserId, String conversationId, int recvMsgOpt) {
        delegate.setRecvMsgOpt(ownerUserId, conversationId, recvMsgOpt);
        invalidateConversation(ownerUserId, conversationId);
    }

    @Override
    public void setBurnDuration(String ownerUserId, String conversationId, int burnDuration) {
        delegate.setBurnDuration(ownerUserId, conversationId, burnDuration);
        invalidateConversation(ownerUserId, conversationId);
    }

    @Override
    public void createSingleConversation(String ownerUserId, String targetUserId, String conversationId) {
        delegate.createSingleConversation(ownerUserId, targetUserId, conversationId);
        invalidateConversation(ownerUserId, conversationId);
    }

    @Override
    public void createGroupConversations(List<String> memberIds, String groupId, String conversationId) {
        delegate.createGroupConversations(memberIds, groupId, conversationId);
        if (memberIds == null) {
            return;
        }
        for (String memberId : memberIds) {
            invalidateConversation(memberId, conversationId);
        }
    }

    @Override
    public void deleteConversation(String ownerUserId, String conversationId) {
        delegate.deleteConversation(ownerUserId, conversationId);
        invalidateConversation(ownerUserId, conversationId);
    }

    @Override
    public long getReadSeq(String ownerUserId, String conversationId) {
        return delegate.getReadSeq(ownerUserId, conversationId);
    }

    @Override
    public int getTotalUnreadCount(String userId) {
        return delegate.getTotalUnreadCount(userId);
    }

    @Override
    public int getUnreadCount(String ownerUserId, String conversationId) {
        Conversation conversation = getConversation(ownerUserId, conversationId);
        return conversation != null ? (int) conversation.getUnreadCount() : 0;
    }

    @Override
    public IncrementalSyncResult<Conversation> getIncrementalConversations(String ownerUserId, long version) {
        return delegate.getIncrementalConversations(ownerUserId, version);
    }

    private void invalidateConversation(String ownerUserId, String conversationId) {
        listCache.invalidate(ownerUserId);
        conversationCache.invalidate(conversationKey(ownerUserId, conversationId));
    }

    private static String conversationKey(String ownerUserId, String conversationId) {
        return ownerUserId + ":" + conversationId;
    }
}
