package com.im.core.webhook;

import com.im.api.IWebhookManager;
import com.im.api.ImHeaders;
import com.im.common.util.IMExecutors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Webhook 管理器 —— 通过 HTTP POST 通知外部业务服务。
 *
 * 对应 OpenIM 的 webhook 机制：在消息发送等关键节点以 HTTP POST 方式调用外部 URL。
 *
 * 工作模式：
 *   · before 类事件：同步阻塞，HTTP 返回非 2xx 视为阻断操作
 *   · after 类事件：虚拟线程异步发送，不阻塞主流程
 *
 * 配置：
 *   webhookBaseUrl — webhook 接收端的基础 URL
 *   实际请求路径：{webhookBaseUrl}/{eventName}（全小写）
 *   示例：POST http://localhost:9000/callback/before_send_single_msg
 *   请求头：Content-Type: application/json
 *   超时：before=5s, after=2s
 *
 * 若 webhookBaseUrl 为空，则所有请求直接放行（no-op 模式）。
 */
public class LocalWebhookManager implements IWebhookManager {

    private static final Logger log = LoggerFactory.getLogger(LocalWebhookManager.class);

    private static final Duration BEFORE_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration AFTER_TIMEOUT = Duration.ofSeconds(2);

    private final String webhookBaseUrl;
    private final HttpClient httpClient;

    public LocalWebhookManager() {
        this(null);
    }

    public LocalWebhookManager(String webhookBaseUrl) {
        this.webhookBaseUrl = webhookBaseUrl;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
    }

    @Override
    public boolean callBefore(Event event, String payload) {
        if (webhookBaseUrl == null || webhookBaseUrl.isBlank()) {
            return true; // 未配置，放行
        }
        try {
            String url = buildUrl(event);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header(ImHeaders.CONTENT_TYPE, ImHeaders.APPLICATION_JSON)
                    .timeout(BEFORE_TIMEOUT)
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            int status = resp.statusCode();

            if (status >= 200 && status < 300) {
                log.debug("Webhook before OK: event={}, status={}, endpoint={}", event, status, safeEndpoint(url));
                return true;
            } else {
                log.warn("Webhook before BLOCKED: event={}, status={}, responseBytes={}, endpoint={}",
                        event, status, bodySize(resp.body()), safeEndpoint(url));
                return false;
            }
        } catch (Exception e) {
            log.warn("Webhook before failed (will ALLOW): event={}, error={}", event, e.toString());
            return true; // 网络超时/异常时放行（防连锁故障）
        }
    }

    @Override
    public void callAfterAsync(Event event, String payload) {
        if (webhookBaseUrl == null || webhookBaseUrl.isBlank()) {
            return;
        }
        String url = buildUrl(event);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header(ImHeaders.CONTENT_TYPE, ImHeaders.APPLICATION_JSON)
                .timeout(AFTER_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();

        // after webhook 是外部 HTTP 调用，放到虚拟线程避免阻塞主业务链路。
        IMExecutors.startVirtualThread("webhook-after-" + event.name().toLowerCase(), () -> {
            try {
                HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                    log.debug("Webhook after OK: event={}, status={}", event, resp.statusCode());
                } else {
                    log.warn("Webhook after failed: event={}, status={}, responseBytes={}",
                            event, resp.statusCode(), bodySize(resp.body()));
                }
            } catch (Exception e) {
                log.warn("Webhook after error: event={}, error={}", event, e.toString());
            }
        });
    }

    /**
     * 构建请求 URL：{webhookBaseUrl}/{eventName}
     * eventName 格式：全小写，如 before_send_single_msg
     */
    private String buildUrl(Event event) {
        String base = webhookBaseUrl.endsWith("/") ? webhookBaseUrl : webhookBaseUrl + "/";
        String eventName = event.name().toLowerCase();
        return base + eventName;
    }

    private static int bodySize(String body) {
        return body != null ? body.length() : 0;
    }

    private static String safeEndpoint(String url) {
        try {
            URI uri = URI.create(url);
            StringBuilder endpoint = new StringBuilder();
            if (uri.getScheme() != null) {
                endpoint.append(uri.getScheme()).append("://");
            }
            if (uri.getHost() != null) {
                endpoint.append(uri.getHost());
            }
            if (uri.getPort() >= 0) {
                endpoint.append(':').append(uri.getPort());
            }
            if (uri.getPath() != null) {
                endpoint.append(uri.getPath());
            }
            return endpoint.isEmpty() ? "configured" : endpoint.toString();
        } catch (Exception ignored) {
            return "configured";
        }
    }
}
