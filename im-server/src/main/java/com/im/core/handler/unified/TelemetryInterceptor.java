package com.im.core.handler.unified;

import com.im.api.ApiInterceptor;
import com.im.api.ApiRequest;
import com.im.api.ProtocolFields;
import com.im.core.observability.RequestObservability;
import io.opentelemetry.api.trace.Span;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * OpenTelemetry 拦截器：为当前 span 注入用户行为属性。
 * 必须先于认证执行，确保 auth 失败等请求也有 trace 记录。
 */
public class TelemetryInterceptor implements ApiInterceptor {

    private static final Logger log = LoggerFactory.getLogger(TelemetryInterceptor.class);

    public static final int ORDER = Integer.MIN_VALUE;

    @Override
    public String name() {
        return "TelemetryInterceptor";
    }

    @Override
    public int order() {
        return ORDER;
    }

    @Override
    public boolean preHandle(ApiRequest request) {
        Span span = Span.current();
        if (!span.isRecording()) {
            return true;
        }

        String operation = request.operation();
        span.updateName(operation);

        for (Map.Entry<String, Object> entry : requestAttributes(request).entrySet()) {
            span.setAttribute(entry.getKey(), String.valueOf(entry.getValue()));
        }

        return true;
    }

    static Map<String, Object> requestAttributes(ApiRequest request) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("app.operation", request.operation());
        attributes.put("app.protocol", RequestObservability.protocol(request));
        attributes.put("app.request.id", RequestObservability.requestId(request));
        attributes.put("app.trace.id", RequestObservability.traceId(request));
        attributes.put("app.client.ip", RequestObservability.clientIp(request));

        // 从 params 中提取关键业务参数（不记敏感数据）
        if (request.params().containsKey(ProtocolFields.CONVERSATION_ID)) {
            attributes.put("app.conversation.id", request.params().get(ProtocolFields.CONVERSATION_ID));
        }
        if (request.params().containsKey(ProtocolFields.GROUP_ID)) {
            attributes.put("app.group.id", request.params().get(ProtocolFields.GROUP_ID));
        }
        return attributes;
    }

    @Override
    public void afterCompletion(ApiRequest request, Object result, Exception error) {
        Span span = Span.current();
        if (span.isRecording()) {
            for (Map.Entry<String, Object> entry : completionAttributes(request, error).entrySet()) {
                if (entry.getValue() instanceof Boolean bool) {
                    span.setAttribute(entry.getKey(), bool);
                } else {
                    span.setAttribute(entry.getKey(), String.valueOf(entry.getValue()));
                }
            }
            if (error != null) {
                span.recordException(error);
            }
        }
    }

    static Map<String, Object> completionAttributes(ApiRequest request, Exception error) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        String userId = request.currentUserId();
        if (userId != null) {
            attributes.put("app.user.id", userId);
        }
        if (error != null) {
            attributes.put("app.error", true);
        }
        return attributes;
    }
}
