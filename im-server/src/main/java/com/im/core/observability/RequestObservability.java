package com.im.core.observability;

import com.im.api.ApiRequest;
import com.im.common.trace.TraceIds;
import org.slf4j.MDC;

import java.util.LinkedHashMap;
import java.util.Map;

public final class RequestObservability {

    private RequestObservability() {
    }

    public static String requestId(ApiRequest request) {
        return attr(request, ApiRequest.ATTR_REQUEST_ID);
    }

    public static String traceId(ApiRequest request) {
        String traceId = attr(request, ApiRequest.ATTR_TRACE_ID);
        if (traceId == null) {
            traceId = TraceIds.next();
            request.setAttribute(ApiRequest.ATTR_TRACE_ID, traceId);
        }
        return traceId;
    }

    public static String userId(ApiRequest request) {
        return attr(request, ApiRequest.ATTR_USER_ID);
    }

    public static String operation(ApiRequest request) {
        return request != null ? request.operation() : null;
    }

    public static String protocol(ApiRequest request) {
        String protocol = attr(request, ApiRequest.ATTR_PROTOCOL);
        return protocol != null ? protocol : "unknown";
    }

    public static String nodeId(ApiRequest request) {
        String nodeId = attr(request, ApiRequest.ATTR_NODE_ID);
        return nodeId != null ? nodeId : "unknown";
    }

    public static String clientIp(ApiRequest request) {
        return attr(request, ApiRequest.ATTR_CLIENT_IP);
    }

    public static String attr(ApiRequest request, String key) {
        if (request == null || key == null) {
            return null;
        }
        Object value = request.attribute(key);
        if (value == null) {
            return null;
        }
        String text = value.toString();
        return text.isBlank() ? null : text;
    }

    public static Scope bindMdc(ApiRequest request) {
        Map<String, String> previous = new LinkedHashMap<>();
        put(previous, LogFields.MDC_REQUEST_ID, requestId(request));
        put(previous, LogFields.MDC_TRACE_ID, traceId(request));
        put(previous, LogFields.MDC_OPERATION, operation(request));
        put(previous, LogFields.MDC_USER_ID, userId(request));
        put(previous, LogFields.MDC_PROTOCOL, protocol(request));
        put(previous, LogFields.MDC_NODE_ID, nodeId(request));
        put(previous, LogFields.MDC_CLIENT_IP, clientIp(request));
        put(previous, LogFields.MDC_CONNECTION_ID, attr(request, ApiRequest.ATTR_CONNECTION_ID));
        put(previous, LogFields.MDC_WS_SEQ, attr(request, ApiRequest.ATTR_WS_SEQ));
        return new Scope(previous);
    }

    private static void put(Map<String, String> previous, String key, String value) {
        previous.put(key, MDC.get(key));
        if (value == null || value.isBlank()) {
            MDC.remove(key);
        } else {
            MDC.put(key, value);
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
