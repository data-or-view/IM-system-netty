package com.im.core.store;

import com.im.api.IMessageStore;
import com.im.api.Message;
import com.im.api.SearchMessagesParam;
import com.im.api.SearchMessagesResult;
import com.im.common.exception.ValidationException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MessageStorePortAdapterTest {

    @Test
    void singleStoreRejectsGroupMessagesAndDelegatesSingleMessages() {
        RecordingMessageStore delegate = new RecordingMessageStore();
        SingleMessageStoreAdapter store = new SingleMessageStoreAdapter(delegate);

        Message single = Message.createSingle("u1", "u2", "single_u1_u2", 101, "{}", 1);
        store.saveSingleMessage(single);

        assertEquals(List.of(single), delegate.saved);

        Message group = Message.createGroup("u1", "g1", "group_g1", 101, "{}", 1);
        assertThrows(ValidationException.class, () -> store.saveSingleMessage(group));
    }

    @Test
    void groupStoreRejectsSingleMessagesAndDelegatesGroupMessages() {
        RecordingMessageStore delegate = new RecordingMessageStore();
        GroupMessageStoreAdapter store = new GroupMessageStoreAdapter(delegate);

        Message group = Message.createGroup("u1", "g1", "group_g1", 101, "{}", 1);
        store.saveGroupMessage(group);

        assertEquals(List.of(group), delegate.saved);

        Message single = Message.createSingle("u1", "u2", "single_u1_u2", 101, "{}", 1);
        assertThrows(ValidationException.class, () -> store.saveGroupMessage(single));
    }

    private static final class RecordingMessageStore implements IMessageStore {
        private final List<Message> saved = new ArrayList<>();

        @Override
        public void save(Message msg) {
            saved.add(msg);
        }

        @Override
        public List<Message> pullOffline(String userId, int limit) {
            return List.of();
        }

        @Override
        public List<Message> pullBySequence(String conversationId, long startSeq, long endSeq, int limit) {
            return List.of();
        }

        @Override
        public void markDelivered(String userId, List<String> msgIds) {
        }

        @Override
        public SearchMessagesResult searchMessages(SearchMessagesParam param) {
            return SearchMessagesResult.empty();
        }
    }
}
