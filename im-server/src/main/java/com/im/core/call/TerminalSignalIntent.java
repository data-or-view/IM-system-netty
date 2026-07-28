package com.im.core.call;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.im.api.ConversationIds;
import com.im.api.Message;
import com.im.api.SignalingAction;
import com.im.api.content.ContentType;
import com.im.core.serialization.jackson.ObjectMapperProvider;

import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;

public record TerminalSignalIntent(String roomId,
                                   String actorId,
                                   String peerUserId,
                                   SignalingAction action,
                                   String clientMsgId,
                                   String messageJson,
                                   String requestContentBase64) {

    private static final ObjectMapper MAPPER = ObjectMapperProvider.get();
    private static final TypeReference<Map<String, Object>> MESSAGE_MAP = new TypeReference<>() { };

    public TerminalSignalIntent(String roomId, String actorId, String peerUserId,
                                SignalingAction action, String clientMsgId) {
        this(roomId, actorId, peerUserId, action, clientMsgId, null, null);
    }

    public TerminalSignalIntent(String roomId, String actorId, String peerUserId,
                                SignalingAction action, String clientMsgId, String messageJson) {
        this(roomId, actorId, peerUserId, action, clientMsgId, messageJson, null);
    }

    public static TerminalSignalIntent withMessage(String roomId, String actorId, String peerUserId,
                                                   SignalingAction action, String clientMsgId, Message message) {
        if (message == null) throw new IllegalArgumentException("terminal signal message is required");
        byte[] body = message.getBody();
        if (body == null || body.length == 0) {
            throw new IllegalArgumentException("terminal signal message body is required");
        }
        try {
            return new TerminalSignalIntent(roomId, actorId, peerUserId, action, clientMsgId,
                    MAPPER.writeValueAsString(message.toJsonMap()), Base64.getEncoder().encodeToString(body));
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize terminal signal message", e);
        }
    }

    public Message message() {
        if (messageJson == null || messageJson.isBlank()) {
            throw new IllegalStateException("pending terminal signal has no prepared message");
        }
        try {
            return Message.fromJsonMap(MAPPER.readValue(messageJson, MESSAGE_MAP));
        } catch (Exception e) {
            throw new IllegalStateException("failed to deserialize terminal signal message", e);
        }
    }

    /** Restores the fingerprint required by modern request indexes from a legacy stored message. */
    public TerminalSignalIntent withDerivedRequestContentFingerprint() {
        if (requestContentBase64 != null && !requestContentBase64.isBlank()) {
            return this;
        }
        byte[] body = message().getBody();
        if (body == null || body.length == 0) {
            throw new IllegalStateException("stored signal message body is required");
        }
        return new TerminalSignalIntent(roomId, actorId, peerUserId, action, clientMsgId,
                messageJson, Base64.getEncoder().encodeToString(body));
    }

    public boolean matchesRequest(String requestActorId, String requestPeerUserId,
                                  SignalingAction requestAction, String requestClientMsgId,
                                  byte[] serializedContent) {
        if (!Objects.equals(roomId, messageRoomId(serializedContent))
                || !Objects.equals(actorId, requestActorId)
                || !Objects.equals(peerUserId, requestPeerUserId)
                || action != requestAction
                || !Objects.equals(clientMsgId, requestClientMsgId)) {
            return false;
        }
        if (requestContentBase64 != null
                && !Objects.equals(requestContentBase64, Base64.getEncoder().encodeToString(serializedContent))) {
            return false;
        }
        Message prepared = message();
        return Objects.equals(clientMsgId, prepared.getMessageId())
                && Objects.equals(actorId, prepared.getFromUserId())
                && Objects.equals(peerUserId, prepared.getToUserId())
                && Objects.equals(ConversationIds.single(actorId, peerUserId), prepared.getConversationId())
                && prepared.getContentType() == ContentType.SIGNAL.getId()
                && Arrays.equals(serializedContent, prepared.getBody());
    }

    private String messageRoomId(byte[] serializedContent) {
        if (serializedContent == null || serializedContent.length == 0) return null;
        try {
            JsonNode value = MAPPER.readTree(serializedContent).get("roomId");
            return value != null && value.isTextual() ? value.textValue() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
