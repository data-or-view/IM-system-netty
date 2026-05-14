package com.im.core.usecase;

import com.im.api.Conversation;
import com.im.api.IConversationManager;

import java.util.List;

public class ConversationGetUseCase {

    private final IConversationManager conversationManager;

    public ConversationGetUseCase(IConversationManager conversationManager) {
        this.conversationManager = conversationManager;
    }

    public List<Conversation> execute(String userId) {
        return conversationManager.getConversations(userId);
    }
}
