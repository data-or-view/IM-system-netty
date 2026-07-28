package com.im.core.handler.unified;

import com.im.api.ApiRequest;
import com.im.api.IChatSendPolicy;
import com.im.api.ICallManager;
import com.im.api.IMessageQueue;
import com.im.api.ISequenceManager;
import com.im.api.Message;
import com.im.api.Operation;
import com.im.api.QueueMessageHandler;
import com.im.api.RoomInformation;
import com.im.common.exception.ForbiddenException;
import com.im.core.call.CallStateManager;
import com.im.core.call.SingleCallSession;
import com.im.core.call.SingleCallStateStore;
import com.im.core.handler.WebhookService;
import com.im.core.usecase.SendMessageUseCase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChatHandlerCallSignalTest {

    private RecordingCallStateStore callStateStore;
    private CallStateManager callStateManager;

    @AfterEach
    void tearDown() {
        if (callStateManager != null) {
            callStateManager.shutdown();
        }
    }

    @Test
    void rejectsHangupFromNonParticipantBeforePublishingMessage() {
        ChatHandler handler = handler(new AllowPolicy());
        callStateStore.session = ringing();
        ApiRequest request = signalRequest("mallory", "caller", "HANGUP");

        assertThrows(ForbiddenException.class, () -> handler.handle(request));

        assertEquals(0, callStateStore.endCalls);
        assertEquals(0, ((RecordingMessageQueue) queue).published.size());
    }

    @Test
    void doesNotEndCallWhenAuthorizedSignalFailsMessageSend() {
        ChatHandler handler = handler(new DenyPolicy());
        callStateStore.session = ringing();
        ApiRequest request = signalRequest("caller", "callee", "CANCEL");

        assertThrows(ForbiddenException.class, () -> handler.handle(request));

        assertEquals(0, callStateStore.endCalls);
    }

    @Test
    void acceptFromCalleePublishesSignalThenMarksCallAccepted() {
        ChatHandler handler = handler(new AllowPolicy());
        callStateStore.session = ringing();
        ApiRequest request = signalRequest("callee", "caller", "ACCEPT");

        @SuppressWarnings("unchecked")
        Map<String, Object> response = (Map<String, Object>) handler.handle(request);

        assertEquals("RECEIVED", response.get("status"));
        assertEquals(1, callStateStore.acceptCalls);
        assertEquals("callee", callStateStore.acceptActorId);
        assertEquals(2, ((RecordingMessageQueue) queue).published.size());
    }

    private final RecordingMessageQueue queue = new RecordingMessageQueue();

    private ChatHandler handler(IChatSendPolicy policy) {
        callStateStore = new RecordingCallStateStore();
        callStateManager = new CallStateManager(queue, callStateStore, 60);
        SendMessageUseCase useCase = new SendMessageUseCase(
                queue,
                new IncrementingSequenceManager(),
                new WebhookService(null),
                policy);
        return new ChatHandler(useCase, new NoopCallManager(), callStateManager);
    }

    private ApiRequest signalRequest(String actorId, String toUserId, String action) {
        ApiRequest request = new ApiRequest(
                Operation.CHAT_SEND,
                Map.of(
                        "toUserId", toUserId,
                        "clientMsgId", "client-" + actorId + "-" + action,
                        "_ct", "signal",
                        "content", Map.of("action", action, "roomId", "room-1")
                ),
                Map.of(),
                null,
                null);
        request.setAttribute(ApiRequest.ATTR_USER_ID, actorId);
        return request;
    }

    private SingleCallSession ringing() {
        return new SingleCallSession("room-1", "caller", "callee", "voice",
                SingleCallSession.STATUS_RINGING, "ws://sfu", System.currentTimeMillis(), 0);
    }

    private static final class RecordingCallStateStore implements SingleCallStateStore {
        SingleCallSession session;
        int acceptCalls;
        int endCalls;
        String acceptActorId;
        String endActorId;

        @Override public SingleCallSession getByRoom(String roomId) { return session; }
        @Override public SingleCallSession getActiveByUser(String userId) { return null; }
        @Override public SingleCallSession createIfUsersIdle(SingleCallSession session) { this.session = session; return session; }
        @Override public SingleCallSession accept(String roomId) { acceptCalls++; return session.accept(System.currentTimeMillis()); }
        @Override public SingleCallSession acceptBy(String roomId, String actorId) {
            acceptActorId = actorId;
            return SingleCallStateStore.super.acceptBy(roomId, actorId);
        }
        @Override public SingleCallSession timeoutIfRinging(String roomId) { return null; }
        @Override public List<SingleCallSession> claimExpiredRinging(long nowEpochMillis, int limit) { return List.of(); }
        @Override public SingleCallSession end(String roomId) { endCalls++; return session.end(); }
        @Override public SingleCallSession endBy(String roomId, String actorId) {
            endActorId = actorId;
            return SingleCallStateStore.super.endBy(roomId, actorId);
        }
    }

    private static final class RecordingMessageQueue implements IMessageQueue {
        private final List<Message> published = new ArrayList<>();

        @Override public void start() {}
        @Override public void stop() {}
        @Override public void publish(String topic, Message msg) { published.add(msg); }
        @Override public void subscribe(String topic, QueueMessageHandler handler) {}
        @Override public void unsubscribe(String topic, QueueMessageHandler handler) {}
        @Override public boolean hasSubscribers(String topic) { return false; }
    }

    private static final class IncrementingSequenceManager implements ISequenceManager {
        private long seq;

        @Override public long nextSequence(String conversationId) { return ++seq; }
        @Override public long getMaximumSequence(String conversationId) { return seq; }
    }

    private static final class AllowPolicy implements IChatSendPolicy {
        @Override public void requireCanSendSingle(String fromUserId, String toUserId) {}
        @Override public void requireCanSendGroup(String fromUserId, String groupId) {}
    }

    private static final class DenyPolicy implements IChatSendPolicy {
        @Override public void requireCanSendSingle(String fromUserId, String toUserId) {
            throw new ForbiddenException("denied");
        }
        @Override public void requireCanSendGroup(String fromUserId, String groupId) {}
    }

    private static final class NoopCallManager implements ICallManager {
        @Override public RoomInformation createRoom(String callerId, String calleeId, String roomId) {
            return new RoomInformation(roomId != null ? roomId : "room-1", "ws://sfu", "caller-token", "callee-token");
        }
        @Override public String issueToken(String userId, String roomId) { return "token"; }
        @Override public String getProviderName() { return "test"; }
        @Override public String getSfuEndpoint() { return "ws://sfu"; }
    }
}
