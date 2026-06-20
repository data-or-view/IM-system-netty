package com.im.core.conversation;

import com.im.api.Conversation;

import java.util.List;

public class ConversationListSnapshot {

    private List<Conversation> conversations;

    public ConversationListSnapshot() {
    }

    public ConversationListSnapshot(List<Conversation> conversations) {
        this.conversations = List.copyOf(conversations);
    }

    public List<Conversation> getConversations() {
        return conversations != null ? conversations : List.of();
    }

    public void setConversations(List<Conversation> conversations) {
        this.conversations = conversations;
    }
}
