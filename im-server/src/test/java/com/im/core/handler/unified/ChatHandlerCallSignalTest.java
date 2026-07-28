package com.im.core.handler.unified;

import com.im.api.ApiRequest;
import com.im.api.BusinessMessageDlqStore;
import com.im.api.IChatSendPolicy;
import com.im.api.ICallManager;
import com.im.api.IMessageQueue;
import com.im.api.ISequenceManager;
import com.im.api.Message;
import com.im.api.MessageQueueTopics;
import com.im.api.Operation;
import com.im.api.QueueMessageHandler;
import com.im.api.RoomInformation;
import com.im.api.SendMessageIdempotency;
import com.im.api.SignalingAction;
import com.im.common.exception.ConflictException;
import com.im.common.exception.ForbiddenException;
import com.im.common.exception.InfrastructureException;
import com.im.common.retry.RetryConfig;
import com.im.common.retry.RetryExecutor;
import com.im.core.call.CallStateManager;
import com.im.core.call.SingleCallSession;
import com.im.core.call.SingleCallStateStore;
import com.im.core.call.TerminalSignalIntent;
import com.im.core.handler.WebhookService;
import com.im.core.usecase.SendMessageUseCase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    void acceptFromCalleeMarksCallAcceptedAndPublishesSignal() {
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

    @Test
    void doesNotPublishAcceptWhenTimeoutAlreadyWonRedisTransition() {
        ChatHandler handler = handler(new AllowPolicy());
        callStateStore.session = ringing();
        callStateStore.acceptSucceeds = false;
        ApiRequest request = signalRequest("callee", "caller", "ACCEPT");

        assertThrows(ConflictException.class, () -> handler.handle(request));

        assertEquals(0, ((RecordingMessageQueue) queue).published.size());
    }

    @Test
    void doesNotPublishCancelWhenTimeoutAlreadyWonRedisTransition() {
        ChatHandler handler = handler(new AllowPolicy());
        callStateStore.session = ringing();
        callStateStore.endSucceeds = false;
        ApiRequest request = signalRequest("caller", "callee", "CANCEL");

        assertThrows(ConflictException.class, () -> handler.handle(request));

        assertEquals(0, ((RecordingMessageQueue) queue).published.size());
        assertEquals(SingleCallSession.STATUS_RINGING, callStateStore.session.status());
    }

    @Test
    void retriesFailedTerminalPublishFromPendingIntentThenClearsIt() {
        ChatHandler handler = handler(new AllowPolicy());
        callStateStore.session = ringing();
        queue.failuresRemaining = 1;
        ApiRequest request = signalRequest("caller", "callee", "CANCEL");

        assertThrows(InfrastructureException.class, () -> handler.handle(request));

        TerminalSignalIntent pending = callStateStore.pendingSignal;
        assertNotNull(pending);
        assertEquals("client-caller-CANCEL", pending.clientMsgId());
        assertEquals(1, callStateStore.endCalls);
        assertEquals(0, queue.published.size());

        @SuppressWarnings("unchecked")
        Map<String, Object> response = (Map<String, Object>) handler.handle(request);

        assertEquals("RECEIVED", response.get("status"));
        assertEquals(1, callStateStore.endCalls, "retry must resume rather than repeat the state transition");
        assertEquals(2, callStateStore.terminalTransitionCalls);
        assertEquals(1, callStateStore.terminalAcknowledgements);
        assertNull(callStateStore.pendingSignal);
        assertEquals(2, queue.published.size());
    }

    @Test
    void retriesPendingTerminalSignalWhenPolicyChangesAfterTheTransition() {
        TogglePolicy policy = new TogglePolicy();
        ChatHandler handler = handler(policy);
        callStateStore.session = ringing();
        queue.failuresRemaining = 1;
        ApiRequest request = signalRequest("caller", "callee", "CANCEL");

        assertThrows(InfrastructureException.class, () -> handler.handle(request));
        assertNotNull(callStateStore.pendingSignal);
        assertEquals(1, callStateStore.endCalls);

        policy.allow = false;

        @SuppressWarnings("unchecked")
        Map<String, Object> response = (Map<String, Object>) handler.handle(request);

        assertEquals("RECEIVED", response.get("status"));
        assertNull(callStateStore.pendingSignal);
        assertEquals(1, callStateStore.endCalls);
    }

    @Test
    void differentTerminalRequestCannotReplayOrOverwritePendingIntent() {
        ChatHandler handler = handler(new AllowPolicy());
        callStateStore.session = ringing();
        queue.failuresRemaining = 1;
        ApiRequest original = signalRequest("caller", "callee", "CANCEL");

        assertThrows(InfrastructureException.class, () -> handler.handle(original));
        TerminalSignalIntent pending = callStateStore.pendingSignal;
        assertNotNull(pending);

        ApiRequest different = signalRequest("caller", "callee", "HANGUP");
        assertThrows(ConflictException.class, () -> handler.handle(different));

        assertEquals(pending, callStateStore.pendingSignal);
        assertEquals(1, callStateStore.endCalls);
        assertEquals(0, queue.published.size());
    }

    @Test
    void sameTerminalRequestIdentityCannotReplayChangedSignalPayload() {
        ChatHandler handler = handler(new AllowPolicy());
        callStateStore.session = ringing();
        queue.failuresRemaining = 1;
        ApiRequest original = signalRequest("caller", "callee", "CANCEL", "client-terminal-same", "original");

        assertThrows(InfrastructureException.class, () -> handler.handle(original));
        TerminalSignalIntent pending = callStateStore.pendingSignal;
        assertNotNull(pending);

        ApiRequest changed = signalRequest("caller", "callee", "CANCEL", "client-terminal-same", "changed");
        assertThrows(ConflictException.class, () -> handler.handle(changed));

        assertEquals(pending, callStateStore.pendingSignal);
        assertEquals(1, callStateStore.endCalls);
        assertEquals(0, queue.published.size());
    }

    @Test
    void failedTerminalRequestCannotReuseItsIdentityForAnotherRoom() {
        ChatHandler handler = handler(new AllowPolicy());
        callStateStore.session = ringing();
        queue.failuresRemaining = 1;
        ApiRequest original = signalRequest("caller", "callee", "CANCEL", "client-cross-room", null);

        assertThrows(InfrastructureException.class, () -> handler.handle(original));
        callStateStore.session = ringing("room-2");

        ApiRequest changedRoom = signalRequest("caller", "callee", "CANCEL", "client-cross-room", null, "room-2");
        assertThrows(ConflictException.class, () -> handler.handle(changedRoom));

        assertEquals("room-2", callStateStore.session.roomId());
        assertEquals(1, callStateStore.endCalls);
    }

    @Test
    void failedTerminalRequestCannotReuseItsIdentityForIce() {
        ChatHandler handler = handler(new AllowPolicy());
        callStateStore.session = ringing();
        queue.failuresRemaining = 1;
        ApiRequest original = signalRequest("caller", "callee", "CANCEL", "client-terminal-to-ice", null);

        assertThrows(InfrastructureException.class, () -> handler.handle(original));

        ApiRequest changedAction = signalRequest("caller", "callee", "ICE", "client-terminal-to-ice", null);
        assertThrows(ConflictException.class, () -> handler.handle(changedAction));

        assertEquals(1, callStateStore.endCalls);
        assertEquals(0, queue.published.size());
    }

    @Test
    void acknowledgedTerminalRequestCannotReuseIdentityWithChangedPayload() {
        ChatHandler handler = handler(new AllowPolicy(), new CachingIdempotency());
        callStateStore.session = ringing();
        ApiRequest original = signalRequest("caller", "callee", "CANCEL", "client-after-ack", "original");

        handler.handle(original);

        ApiRequest changedPayload = signalRequest("caller", "callee", "CANCEL", "client-after-ack", "changed");
        assertThrows(ConflictException.class, () -> handler.handle(changedPayload));

        assertEquals(1, callStateStore.endCalls);
        assertEquals(2, queue.published.size());
    }

    @Test
    void acknowledgedTerminalRequestCannotReuseActorAndClientIdentityWithChangedPeer() {
        ChatHandler handler = handler(new AllowPolicy(), new CachingIdempotency());
        callStateStore.session = ringing();
        ApiRequest original = signalRequest("caller", "callee", "CANCEL", "client-changed-peer", null);

        handler.handle(original);

        ApiRequest changedPeer = signalRequest("caller", "other-callee", "CANCEL", "client-changed-peer", null);
        assertThrows(ConflictException.class, () -> handler.handle(changedPeer));

        assertEquals(1, callStateStore.endCalls);
        assertEquals(2, queue.published.size());
    }

    @Test
    void retryAfterAcknowledgeFailureUsesCachedSendAndClearsIntent() {
        ChatHandler handler = handler(new AllowPolicy(), new CachingIdempotency());
        callStateStore.session = ringing();
        callStateStore.acknowledgementFailuresRemaining = 1;
        ApiRequest request = signalRequest("caller", "callee", "CANCEL");

        assertThrows(IllegalStateException.class, () -> handler.handle(request));

        assertNotNull(callStateStore.pendingSignal);
        assertEquals(1, callStateStore.endCalls);
        assertEquals(1, callStateStore.terminalTransitionCalls);
        assertEquals(2, queue.published.size());

        @SuppressWarnings("unchecked")
        Map<String, Object> response = (Map<String, Object>) handler.handle(request);

        assertEquals("RECEIVED", response.get("status"));
        assertEquals(1, callStateStore.endCalls);
        assertEquals(1, callStateStore.terminalTransitionCalls,
                "completed message idempotency must skip the transition hook");
        assertEquals(1, callStateStore.terminalAcknowledgements);
        assertNull(callStateStore.pendingSignal);
        assertEquals(2, queue.published.size());
    }

    @Test
    void retryRecoversWhenDeliverPublishAndFailureRecordingBothFail() {
        BusinessMessageDlqStore failingStore = (topic, message, cause) -> {
            throw new IllegalStateException("failure store unavailable");
        };
        ChatHandler handler = handler(new AllowPolicy(), SendMessageIdempotency.none(), failingStore);
        callStateStore.session = ringing();
        queue.failingTopic = MessageQueueTopics.DELIVER;
        queue.failuresRemaining = 1;
        ApiRequest request = signalRequest("caller", "callee", "CANCEL");

        assertThrows(InfrastructureException.class, () -> handler.handle(request));

        assertNotNull(callStateStore.pendingSignal);
        assertEquals(1, callStateStore.endCalls);
        assertEquals(1, queue.published.size(), "the first PERSIST publication completed");
        Message firstPersist = queue.published.get(0);

        @SuppressWarnings("unchecked")
        Map<String, Object> response = (Map<String, Object>) handler.handle(request);

        assertEquals("RECEIVED", response.get("status"));
        assertEquals(1, callStateStore.endCalls);
        assertNull(callStateStore.pendingSignal);
        assertEquals(3, queue.published.size());
        assertSameMessage(firstPersist, queue.published.get(1));
        assertSameMessage(firstPersist, queue.published.get(2));
    }

    private final RecordingMessageQueue queue = new RecordingMessageQueue();

    private ChatHandler handler(IChatSendPolicy policy) {
        return handler(policy, SendMessageIdempotency.none());
    }

    private ChatHandler handler(IChatSendPolicy policy, SendMessageIdempotency idempotency) {
        return handler(policy, idempotency, BusinessMessageDlqStore.none());
    }

    private ChatHandler handler(IChatSendPolicy policy, SendMessageIdempotency idempotency,
                                BusinessMessageDlqStore failureStore) {
        callStateStore = new RecordingCallStateStore();
        callStateManager = new CallStateManager(queue, callStateStore, 60);
        SendMessageUseCase useCase = new SendMessageUseCase(
                queue,
                new IncrementingSequenceManager(),
                new WebhookService(null),
                policy,
                new NoRetryExecutor(),
                idempotency,
                failureStore);
        return new ChatHandler(useCase, new NoopCallManager(), callStateManager, policy);
    }

    private ApiRequest signalRequest(String actorId, String toUserId, String action) {
        return signalRequest(actorId, toUserId, action, "client-" + actorId + "-" + action, null);
    }

    private ApiRequest signalRequest(String actorId, String toUserId, String action,
                                     String clientMsgId, String reason) {
        return signalRequest(actorId, toUserId, action, clientMsgId, reason, "room-1");
    }

    private ApiRequest signalRequest(String actorId, String toUserId, String action,
                                     String clientMsgId, String reason, String roomId) {
        Map<String, Object> signal = new HashMap<>();
        signal.put("action", action);
        signal.put("roomId", roomId);
        if (reason != null) signal.put("reason", reason);
        ApiRequest request = new ApiRequest(
                Operation.CHAT_SEND,
                Map.of(
                        "toUserId", toUserId,
                        "clientMsgId", clientMsgId,
                        "_ct", "signal",
                        "content", signal
                ),
                Map.of(),
                null,
                null);
        request.setAttribute(ApiRequest.ATTR_USER_ID, actorId);
        return request;
    }

    private static void assertSameMessage(Message expected, Message actual) {
        assertEquals(expected.getMessageId(), actual.getMessageId());
        assertEquals(expected.getSequenceId(), actual.getSequenceId());
        assertEquals(expected.getTimestamp(), actual.getTimestamp());
        assertEquals(expected.getFromUserId(), actual.getFromUserId());
        assertEquals(expected.getToUserId(), actual.getToUserId());
        assertEquals(expected.getGroupId(), actual.getGroupId());
        assertEquals(expected.getConversationId(), actual.getConversationId());
        assertEquals(expected.getContentType(), actual.getContentType());
        assertEquals(expected.getContent(), actual.getContent());
        assertEquals(expected.getMessageSeq(), actual.getMessageSeq());
        assertArrayEquals(expected.getBody(), actual.getBody());
        assertEquals(expected.getStatus(), actual.getStatus());
        assertEquals(expected.getMetadata(), actual.getMetadata());
    }

    private SingleCallSession ringing() {
        return ringing("room-1");
    }

    private SingleCallSession ringing(String roomId) {
        return new SingleCallSession(roomId, "caller", "callee", "voice",
                SingleCallSession.STATUS_RINGING, "ws://sfu", System.currentTimeMillis(), 0);
    }

    private static final class RecordingCallStateStore implements SingleCallStateStore {
        SingleCallSession session;
        int acceptCalls;
        int endCalls;
        int terminalTransitionCalls;
        int terminalAcknowledgements;
        int acknowledgementFailuresRemaining;
        boolean acceptSucceeds = true;
        boolean endSucceeds = true;
        String acceptActorId;
        String endActorId;
        TerminalSignalIntent pendingSignal;
        final Map<String, TerminalSignalIntent> terminalRequests = new HashMap<>();

        @Override public SingleCallSession getByRoom(String roomId) { return session; }
        @Override public SingleCallSession getActiveByUser(String userId) { return null; }
        @Override public SingleCallSession createIfUsersIdle(SingleCallSession session) { this.session = session; return session; }
        @Override public SingleCallSession accept(String roomId) {
            acceptCalls++;
            session = session.accept(System.currentTimeMillis());
            return session;
        }
        @Override public SingleCallSession acceptBy(String roomId, String actorId) {
            acceptActorId = actorId;
            if (!acceptSucceeds) return null;
            return SingleCallStateStore.super.acceptBy(roomId, actorId);
        }
        @Override public SingleCallSession timeoutIfRinging(String roomId) { return null; }
        @Override public List<SingleCallSession> claimExpiredRinging(long nowEpochMillis, int limit) { return List.of(); }
        @Override public SingleCallSession end(String roomId) {
            endCalls++;
            SingleCallSession ended = session.end();
            session = null;
            return ended;
        }
        @Override public SingleCallSession endBy(String roomId, String actorId) {
            endActorId = actorId;
            if (!endSucceeds) return null;
            return SingleCallStateStore.super.endBy(roomId, actorId);
        }

        @Override
        public TerminalSignalIntent getPendingTerminalSignal(String roomId) {
            return pendingSignal != null && pendingSignal.roomId().equals(roomId) ? pendingSignal : null;
        }

        @Override
        public TerminalSignalIntent getTerminalSignalByRequest(String actorId, String peerUserId, String clientMsgId) {
            return terminalRequests.get(requestKey(actorId, clientMsgId));
        }

        @Override
        public boolean transitionTerminalSignal(TerminalSignalIntent intent) {
            terminalTransitionCalls++;
            TerminalSignalIntent request = getTerminalSignalByRequest(
                    intent.actorId(), intent.peerUserId(), intent.clientMsgId());
            if (request != null) {
                return request.roomId().equals(intent.roomId())
                        && request.actorId().equals(intent.actorId())
                        && request.peerUserId().equals(intent.peerUserId())
                        && request.action() == intent.action()
                        && request.clientMsgId().equals(intent.clientMsgId())
                        && request.requestContentBase64().equals(intent.requestContentBase64());
            }
            TerminalSignalIntent roomPending = getPendingTerminalSignal(intent.roomId());
            if (roomPending != null) return roomPending.equals(intent);
            SingleCallSession transitioned = intent.action() == SignalingAction.ACCEPT
                    ? acceptBy(intent.roomId(), intent.actorId())
                    : endBy(intent.roomId(), intent.actorId());
            if (transitioned == null) return false;
            pendingSignal = intent;
            terminalRequests.put(requestKey(intent.actorId(), intent.clientMsgId()), intent);
            return true;
        }

        @Override
        public boolean acknowledgeTerminalSignal(TerminalSignalIntent intent) {
            TerminalSignalIntent request = getTerminalSignalByRequest(
                    intent.actorId(), intent.peerUserId(), intent.clientMsgId());
            if (request == null || !request.roomId().equals(intent.roomId())
                    || !request.actorId().equals(intent.actorId())
                    || !request.peerUserId().equals(intent.peerUserId())
                    || request.action() != intent.action()
                    || !request.clientMsgId().equals(intent.clientMsgId())) {
                return false;
            }
            if (acknowledgementFailuresRemaining > 0) {
                acknowledgementFailuresRemaining--;
                throw new IllegalStateException("acknowledgement unavailable");
            }
            terminalAcknowledgements++;
            if (pendingSignal != null && pendingSignal.roomId().equals(intent.roomId())) {
                pendingSignal = null;
            }
            return true;
        }

        private static String requestKey(String actorId, String clientMsgId) {
            return actorId + '|' + clientMsgId;
        }
    }

    private static final class RecordingMessageQueue implements IMessageQueue {
        private final List<Message> published = new ArrayList<>();
        private int failuresRemaining;
        private String failingTopic;

        @Override public void start() {}
        @Override public void stop() {}
        @Override public void publish(String topic, Message msg) {
            if (failuresRemaining > 0 && (failingTopic == null || failingTopic.equals(topic))) {
                failuresRemaining--;
                throw new IllegalStateException("queue unavailable");
            }
            published.add(msg);
        }
        @Override public void subscribe(String topic, QueueMessageHandler handler) {}
        @Override public void unsubscribe(String topic, QueueMessageHandler handler) {}
        @Override public boolean hasSubscribers(String topic) { return false; }
    }

    private static final class IncrementingSequenceManager implements ISequenceManager {
        private long seq;

        @Override public long nextSequence(String conversationId) { return ++seq; }
        @Override public long getMaximumSequence(String conversationId) { return seq; }
    }

    private static final class NoRetryExecutor implements RetryExecutor {
        @Override
        public <T> T execute(RetryConfig config, java.util.concurrent.Callable<T> callable) {
            try {
                return callable.call();
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private static final class CachingIdempotency implements SendMessageIdempotency {
        private final Map<String, Object> results = new HashMap<>();

        @Override
        public <T> T execute(String idempotencyKey, Supplier<T> action, Class<T> returnType) {
            Object cached = results.get(idempotencyKey);
            if (cached != null) return returnType.cast(cached);
            T result = action.get();
            results.put(idempotencyKey, result);
            return result;
        }
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

    private static final class TogglePolicy implements IChatSendPolicy {
        private boolean allow = true;

        @Override public void requireCanSendSingle(String fromUserId, String toUserId) {
            if (!allow) {
                throw new ForbiddenException("denied after terminal transition");
            }
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
