package com.im.core.observability;

import com.im.api.ApiRequest;
import com.im.api.Message;
import org.slf4j.MDC;

import java.util.LinkedHashMap;
import java.util.Map;

public final class MessageObservability {

    public static final String META_REQUEST_ID = "_obs.requestId";
    public static final String META_TRACE_ID = "_obs.traceId";
    public static final String META_CLIENT_MSG_ID = "_obs.clientMsgId";
    public static final String META_ORIGIN_OPERATION = "_obs.originOperation";
    public static final String META_ORIGIN_NODE_ID = "_obs.originNodeId";
    public static final String META_ORIGIN_PROTOCOL = "_obs.originProtocol";
    public static final String META_ORIGIN_CLIENT_IP = "_obs.originClientIp";

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
        put(fields, LogFields.TOPIC, topic);
        if (message != null) {
            put(fields, LogFields.REQUEST_ID, meta(message, META_REQUEST_ID));
            put(fields, LogFields.TRACE_ID, meta(message, META_TRACE_ID));
            put(fields, LogFields.CLIENT_MSG_ID, meta(message, META_CLIENT_MSG_ID));
            put(fields, LogFields.OPERATION, meta(message, META_ORIGIN_OPERATION));
            put(fields, LogFields.NODE_ID, meta(message, META_ORIGIN_NODE_ID));
            put(fields, LogFields.PROTOCOL, meta(message, META_ORIGIN_PROTOCOL));
            put(fields, LogFields.CLIENT_IP, meta(message, META_ORIGIN_CLIENT_IP));
            put(fields, LogFields.MESSAGE_ID, message.getMessageId());
            put(fields, LogFields.CONVERSATION_ID, message.getConversationId());
            put(fields, LogFields.GROUP_ID, message.getGroupId());
            put(fields, LogFields.FROM_USER_ID, message.getFromUserId());
            put(fields, LogFields.TO_USER_ID, message.getToUserId());
            if (message.getMessageSeq() > 0) {
                fields.put(LogFields.MESSAGE_SEQ, message.getMessageSeq());
            }
            if (message.getSequenceId() > 0) {
                fields.put(LogFields.SEQUENCE_ID, message.getSequenceId());
            }
        }
        return fields;
    }

    public static Object[] fieldPairs(String topic, Message message) {
        Map<String, Object> fields = fields(topic, message);
        Object[] pairs = new Object[fields.size() * 2];
        int index = 0;
        for (Map.Entry<String, Object> entry : fields.entrySet()) {
            pairs[index++] = entry.getKey();
            pairs[index++] = entry.getValue();
        }
        return pairs;
    }

    public static void captureRequestContext(Message message, Map<String, Object> params) {
        if (message == null) {
            return;
        }
        putMeta(message, META_REQUEST_ID, MDC.get(LogFields.MDC_REQUEST_ID));
        putMeta(message, META_TRACE_ID, MDC.get(LogFields.MDC_TRACE_ID));
        String clientMsgId = clientMsgId(params);
        putMeta(message, META_CLIENT_MSG_ID, clientMsgId != null ? clientMsgId : message.getMessageId());
        putMeta(message, META_ORIGIN_OPERATION, MDC.get(LogFields.MDC_OPERATION));
        putMeta(message, META_ORIGIN_NODE_ID, MDC.get(LogFields.MDC_NODE_ID));
        putMeta(message, META_ORIGIN_PROTOCOL, MDC.get(LogFields.MDC_PROTOCOL));
        putMeta(message, META_ORIGIN_CLIENT_IP, MDC.get(LogFields.MDC_CLIENT_IP));
    }

    private static Scope bind(String topic, String streamId, String businessDlqId, Message message) {
        Map<String, String> previous = new LinkedHashMap<>();
        putMdc(previous, LogFields.MDC_TOPIC, topic);
        putMdc(previous, LogFields.MDC_STREAM_ID, streamId);
        putMdc(previous, LogFields.MDC_BUSINESS_DLQ_ID, businessDlqId);
        if (message != null) {
            putMdc(previous, LogFields.MDC_REQUEST_ID, meta(message, META_REQUEST_ID));
            putMdc(previous, LogFields.MDC_TRACE_ID, meta(message, META_TRACE_ID));
            putMdc(previous, LogFields.MDC_OPERATION, meta(message, META_ORIGIN_OPERATION));
            putMdc(previous, LogFields.MDC_NODE_ID, meta(message, META_ORIGIN_NODE_ID));
            putMdc(previous, LogFields.MDC_CLIENT_IP, meta(message, META_ORIGIN_CLIENT_IP));
            putMdc(previous, LogFields.MDC_PROTOCOL, meta(message, META_ORIGIN_PROTOCOL));
            putMdc(previous, LogFields.MDC_CLIENT_MSG_ID, meta(message, META_CLIENT_MSG_ID));
            putMdc(previous, LogFields.MDC_MESSAGE_ID, message.getMessageId());
            putMdc(previous, LogFields.MDC_CONVERSATION_ID, message.getConversationId());
            putMdc(previous, LogFields.MDC_GROUP_ID, message.getGroupId());
            putMdc(previous, LogFields.MDC_FROM_USER_ID, message.getFromUserId());
            putMdc(previous, LogFields.MDC_TO_USER_ID, message.getToUserId());
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

    private static String meta(Message message, String key) {
        return message.getMetadata() != null ? message.getMetadata().get(key) : null;
    }

    private static void putMeta(Message message, String key, String value) {
        if (value != null && !value.isBlank()) {
            message.putMeta(key, value);
        }
    }

    private static String clientMsgId(Map<String, Object> params) {
        if (params == null) {
            return null;
        }
        Object value = params.containsKey("clientMsgId") ? params.get("clientMsgId") : params.get("client_msg_id");
        return value instanceof String text && !text.isBlank() ? text.trim() : null;
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
