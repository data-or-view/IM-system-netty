package com.im.core.observability;

import com.im.api.Message;
import org.slf4j.MDC;

import java.util.LinkedHashMap;
import java.util.Map;

public final class MessageObservability {

    private static final String TOPIC = "mq.topic";
    private static final String STREAM_ID = "mq.stream.id";
    private static final String BUSINESS_DLQ_ID = "business_dlq.id";
    private static final String MESSAGE_ID = "message_id";
    private static final String CONVERSATION_ID = "conversation_id";
    private static final String GROUP_ID = "group_id";
    private static final String FROM_USER_ID = "from_user_id";
    private static final String TO_USER_ID = "to_user_id";

    private MessageObservability() {
    }

    public static Scope bind(String topic, Message message) {
        return bind(topic, null, null, message);
    }

    public static Scope bindStream(String topic, String streamId, Message message) {
        return bind(topic, streamId, null, message);
    }

    public static Scope bindBusinessDlq(String topic, long businessDlqId, Message message) {
        return bind(topic, null, String.valueOf(businessDlqId), message);
    }

    public static Map<String, Object> fields(String topic, Message message) {
        Map<String, Object> fields = new LinkedHashMap<>();
        put(fields, "topic", topic);
        if (message != null) {
            put(fields, "messageId", message.getMessageId());
            put(fields, "conversationId", message.getConversationId());
            put(fields, "groupId", message.getGroupId());
            put(fields, "fromUserId", message.getFromUserId());
            put(fields, "toUserId", message.getToUserId());
            if (message.getMessageSeq() > 0) {
                fields.put("messageSeq", message.getMessageSeq());
            }
            if (message.getSequenceId() > 0) {
                fields.put("sequenceId", message.getSequenceId());
            }
        }
        return fields;
    }

    private static Scope bind(String topic, String streamId, String businessDlqId, Message message) {
        Map<String, String> previous = new LinkedHashMap<>();
        putMdc(previous, TOPIC, topic);
        putMdc(previous, STREAM_ID, streamId);
        putMdc(previous, BUSINESS_DLQ_ID, businessDlqId);
        if (message != null) {
            putMdc(previous, MESSAGE_ID, message.getMessageId());
            putMdc(previous, CONVERSATION_ID, message.getConversationId());
            putMdc(previous, GROUP_ID, message.getGroupId());
            putMdc(previous, FROM_USER_ID, message.getFromUserId());
            putMdc(previous, TO_USER_ID, message.getToUserId());
        }
        return new Scope(previous);
    }

    private static void putMdc(Map<String, String> previous, String key, String value) {
        previous.put(key, MDC.get(key));
        if (value == null || value.isBlank()) {
            MDC.remove(key);
        } else {
            MDC.put(key, value);
        }
    }

    private static void put(Map<String, Object> fields, String key, Object value) {
        if (value instanceof String s && !s.isBlank()) {
            fields.put(key, s);
        } else if (value != null && !(value instanceof String)) {
            fields.put(key, value);
        }
    }

    public static final class Scope implements AutoCloseable {
        private final Map<String, String> previous;

        private Scope(Map<String, String> previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            for (Map.Entry<String, String> entry : previous.entrySet()) {
                if (entry.getValue() == null) {
                    MDC.remove(entry.getKey());
                } else {
                    MDC.put(entry.getKey(), entry.getValue());
                }
            }
        }
    }
}
