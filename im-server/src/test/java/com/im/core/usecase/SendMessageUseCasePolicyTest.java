package com.im.core.usecase;

import com.im.api.IChatSendPolicy;
import com.im.api.IMessageQueue;
import com.im.api.ISequenceManager;
import com.im.api.Message;
import com.im.api.MessageQueueTopics;
import com.im.api.content.TextContent;
import com.im.common.enums.ImErrorCode;
import com.im.common.exception.ImException;
import com.im.core.handler.WebhookService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SendMessageUseCasePolicyTest {

    @Test
    void singleChatRequiresSendPolicyBeforePublishing() {
        RecordingPolicy policy = new RecordingPolicy();
        policy.singleError = new ImException(ImErrorCode.FORBIDDEN, "blocked by target");
        RecordingQueue queue = new RecordingQueue();
        SendMessageUseCase useCase = new SendMessageUseCase(
                queue, new FixedSequenceManager(), new WebhookService(null), policy);

        ImException ex = assertThrows(ImException.class,
                () -> useCase.execute(params("client-a1"), "alice", "bob", null, new TextContent("hi")));

        assertEquals(ImErrorCode.FORBIDDEN, ex.getErrorCode());
        assertEquals(List.of("single|alice|bob"), policy.calls);
        assertEquals(0, queue.published.size());
    }

    @Test
    void groupChatRequiresSendPolicyBeforePublishing() {
        RecordingPolicy policy = new RecordingPolicy();
        policy.groupError = new ImException(ImErrorCode.FORBIDDEN, "not group member");
        RecordingQueue queue = new RecordingQueue();
        SendMessageUseCase useCase = new SendMessageUseCase(
                queue, new FixedSequenceManager(), new WebhookService(null), policy);

        ImException ex = assertThrows(ImException.class,
                () -> useCase.execute(params("client-a2"), "alice", null, "group-1", new TextContent("hi")));

        assertEquals(ImErrorCode.FORBIDDEN, ex.getErrorCode());
        assertEquals(List.of("group|alice|group-1"), policy.calls);
        assertEquals(0, queue.published.size());
    }

    @Test
    void publishesWhenPolicyAllowsSingleChat() {
        RecordingPolicy policy = new RecordingPolicy();
        RecordingQueue queue = new RecordingQueue();
        SendMessageUseCase useCase = new SendMessageUseCase(
                queue, new FixedSequenceManager(), new WebhookService(null), policy);

        SendMessageResult result = useCase.execute(
                params("client-a3"), "alice", "bob", null, new TextContent("hi"));

        assertEquals("client-a3", result.messageId());
        assertEquals("single_alice_bob", result.conversationId());
        assertEquals(2, queue.published.size());
        assertEquals(MessageQueueTopics.PERSIST, queue.published.get(0).topic);
        assertEquals(MessageQueueTopics.DELIVER, queue.published.get(1).topic);
        assertEquals("client-a3", queue.published.get(0).message().getMessageId());
    }

    private static Map<String, Object> params(String clientMsgId) {
        return Map.of("clientMsgId", clientMsgId);
    }

    private static final class RecordingPolicy implements IChatSendPolicy {
        private final List<String> calls = new ArrayList<>();
        private ImException singleError;
        private ImException groupError;

        @Override
        public void requireCanSendSingle(String fromUserId, String toUserId) {
            calls.add("single|" + fromUserId + "|" + toUserId);
            if (singleError != null) throw singleError;
        }

        @Override
        public void requireCanSendGroup(String fromUserId, String groupId) {
            calls.add("group|" + fromUserId + "|" + groupId);
            if (groupError != null) throw groupError;
        }
    }

    private static final class RecordingQueue implements IMessageQueue {
        private final List<Published> published = new ArrayList<>();

        @Override public void start() {}
        @Override public void stop() {}
        @Override public void publishAsync(String topic, Message msg) { published.add(new Published(topic, msg)); }
        @Override public void subscribe(String topic, MessageHandler handler) {}
        @Override public void unsubscribe(String topic, MessageHandler handler) {}
        @Override public boolean hasSubscribers(String topic) { return false; }
    }

    private record Published(String topic, Message message) {}

    private static final class FixedSequenceManager implements ISequenceManager {
        @Override public long nextSequence(String conversationId) { return 7; }
        @Override public long getMaximumSequence(String conversationId) { return 7; }
    }
}
