package com.im.core.usecase;

import com.im.api.Conversation;
import com.im.api.IConversationManager;

public class ConversationSetUseCase {

    private final IConversationManager conversationManager;

    public ConversationSetUseCase(IConversationManager conversationManager) {
        this.conversationManager = conversationManager;
    }

    public void setPinned(String userId, String conversationId, boolean pinned) {
        conversationManager.setPinned(userId, conversationId, pinned);
    }

    public void setRecvMsgOpt(String userId, String conversationId, int recvMsgOpt) {
        conversationManager.setRecvMsgOpt(userId, conversationId, recvMsgOpt);
    }
}
