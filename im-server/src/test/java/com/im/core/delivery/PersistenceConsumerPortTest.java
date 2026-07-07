package com.im.core.delivery;

import com.im.api.IGroupMessageStore;
import com.im.api.IMessageQueue;
import com.im.api.ISingleMessageStore;
import com.im.api.Message;
import com.im.api.MessageQueueTopics;
import com.im.api.QueueMessageHandler;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PersistenceConsumerPortTest {

    @Test
    void dispatchesMessagesToSingleOrGroupStorePort() {
        TestMessageQueue queue = new TestMessageQueue();
        RecordingSingleStore singleStore = new RecordingSingleStore();
        RecordingGroupStore groupStore = new RecordingGroupStore();
        PersistenceConsumer consumer = new PersistenceConsumer(queue, singleStore, groupStore);

        try {
            consumer.start();
            queue.handler(MessageQueueTopics.PERSIST).onMessage(
                    Message.createSingle("u1", "u2", "single_u1_u2", 101, "{}", 1));
            queue.handler(MessageQueueTopics.PERSIST).onMessage(
                    Message.createGroup("u1", "g1", "group_g1", 101, "{}", 2));

            assertEquals(1, singleStore.messages.size());
            assertEquals(1, groupStore.messages.size());
            assertEquals("u2", singleStore.messages.getFirst().getToUserId());
            assertEquals("g1", groupStore.messages.getFirst().getGroupId());
        } finally {
            consumer.stop();
        }
    }

    private static final class RecordingSingleStore implements ISingleMessageStore {
        private final CopyOnWriteArrayList<Message> messages = new CopyOnWriteArrayList<>();

        @Override
        public void saveSingleMessage(Message message) {
            messages.add(message);
        }
    }

    private static final class RecordingGroupStore implements IGroupMessageStore {
        private final CopyOnWriteArrayList<Message> messages = new CopyOnWriteArrayList<>();

        @Override
        public void saveGroupMessage(Message message) {
            messages.add(message);
        }
    }

    private static final class TestMessageQueue implements IMessageQueue {
        private final Map<String, QueueMessageHandler> handlers = new ConcurrentHashMap<>();

        @Override
        public void start() {
        }

        @Override
        public void stop() {
        }

        @Override
        public void publish(String topic, Message msg) {
        }

        @Override
        public void subscribe(String topic, QueueMessageHandler handler) {
            handlers.put(topic, handler);
        }

        @Override
        public void unsubscribe(String topic, QueueMessageHandler handler) {
            handlers.remove(topic);
        }

        @Override
        public boolean hasSubscribers(String topic) {
            return handlers.containsKey(topic);
        }

        QueueMessageHandler handler(String topic) {
            return handlers.get(topic);
        }
    }
}
