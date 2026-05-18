package com.im.core.handler.unified;

import com.im.api.ApiInterceptor;
import com.im.api.ApiRequest;
import io.opentelemetry.api.trace.Span;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OpenTelemetry 拦截器：为当前 span 注入用户行为属性。
 *
 * <p>在所有拦截器之前执行（order = Integer.MIN_VALUE），
 * 确保 span 在 AuthInterceptor 和业务 handler 中已携带上下文。</p>
 */
public class TelemetryInterceptor implements ApiInterceptor {

    private static final Logger log = LoggerFactory.getLogger(TelemetryInterceptor.class);

    @Override
    public String name() {
        return "TelemetryInterceptor";
    }

    @Override
    public int order() {
        return Integer.MIN_VALUE;
    }

    @Override
    public boolean preHandle(ApiRequest request) {
        Span span = Span.current();
        if (!span.isRecording()) {
            return true;
        }

        // 更新 span 名称为业务操作名
        String operation = request.operation();
        span.updateName(operation);

        // 用户行为属性
        span.setAttribute("app.operation", operation);
        span.setAttribute("app.protocol", request.headers().getOrDefault("Content-Type", "ws"));

        String userId = request.currentUserId();
        if (userId != null) {
            span.setAttribute("app.user.id", userId);
        }

        // 从 params 中提取关键业务参数（不记敏感数据）
        if (request.params().containsKey("conversationId")) {
            span.setAttribute("app.conversation.id", String.valueOf(request.params().get("conversationId")));
        }
        if (request.params().containsKey("groupId")) {
            span.setAttribute("app.group.id", String.valueOf(request.params().get("groupId")));
        }

        return true;
    }

    @Override
    public void afterCompletion(ApiRequest request, Object result, Exception error) {
        Span span = Span.current();
        if (span.isRecording()) {
            if (error != null) {
                span.recordException(error);
                span.setAttribute("app.error", true);
            }
        }
    }
}
