package com.im.core.handler;

import com.im.api.IWebhookManager;
import com.im.api.content.IMessageContent;

import java.util.Map;

/**
 * Webhook 调用服务。
 *
 * <p>封装消息发送前后的 webhook 调用逻辑，使用 Map params 参数。</p>
 */
public class WebhookService {

    private final IWebhookManager webhookManager;

    public WebhookService(IWebhookManager webhookManager) {
        this.webhookManager = webhookManager;
    }

    // ── 新接口：Map params（统一 handler 使用） ──

    /** 单聊发送前 webhook（可通过返回 false 阻断）。 */
    public boolean beforeSendSingle(Map<String, Object> params, String fromUserId, String toUserId,
                                    IMessageContent content) {
        if (webhookManager == null) return true;
        String payload = buildPayload(params, fromUserId, toUserId, null, content);
        return webhookManager.callBefore(IWebhookManager.Event.BEFORE_SEND_SINGLE_MSG, payload);
    }

    /** 群聊发送前 webhook（可通过返回 false 阻断）。 */
    public boolean beforeSendGroup(Map<String, Object> params, String fromUserId, String groupId,
                                   IMessageContent content) {
        if (webhookManager == null) return true;
        String payload = buildPayload(params, fromUserId, null, groupId, content);
        return webhookManager.callBefore(IWebhookManager.Event.BEFORE_SEND_GROUP_MSG, payload);
    }

    /** 单聊发送后 webhook（异步通知）。 */
    public void afterSendSingle(Map<String, Object> params, String fromUserId, String toUserId,
                                IMessageContent content) {
        if (webhookManager == null) return;
        String payload = buildPayload(params, fromUserId, toUserId, null, content);
        webhookManager.callAfterAsync(IWebhookManager.Event.AFTER_SEND_SINGLE_MSG, payload);
    }

    /** 群聊发送后 webhook（异步通知）。 */
    public void afterSendGroup(Map<String, Object> params, String fromUserId, String groupId,
                               IMessageContent content) {
        if (webhookManager == null) return;
        String payload = buildPayload(params, fromUserId, null, groupId, content);
        webhookManager.callAfterAsync(IWebhookManager.Event.AFTER_SEND_GROUP_MSG, payload);
    }

    // ── Payload 构建 ──

    /**
     * 从 Map params 构建 webhook payload。
     */
    String buildPayload(Map<String, Object> params, String fromUserId, String toUserId,
                        String groupId, IMessageContent content) {
        StringBuilder sb = new StringBuilder(256);
        sb.append('{');
        appendField(sb, "fromUserId", fromUserId);
        sb.append(',');
        appendField(sb, "toUserId", toUserId);
        if (groupId != null) {
            sb.append(',');
            appendField(sb, "groupId", groupId);
        }
        sb.append(',');
        Object ctObj = params.get("_ct");
        appendField(sb, "contentType", ctObj != null ? ctObj.toString() : null);
        sb.append(',');
        sb.append("\"content\":");
        if (content != null) {
            try {
                String json = com.im.core.serialization.jackson.ObjectMapperProvider.get()
                        .writeValueAsString(content);
                sb.append(json);
            } catch (Exception e) {
                sb.append("null");
            }
        } else {
            sb.append("null");
        }
        sb.append(',');
        Object convIdObj = params.get("conversationId");
        appendField(sb, "conversationId", convIdObj != null ? convIdObj.toString() : null);
        sb.append(',');
        Object seqObj = params.get("_ms");
        appendField(sb, "seq", seqObj != null ? seqObj.toString() : null);
        sb.append('}');
        return sb.toString();
    }

    private void appendField(StringBuilder sb, String key, String value) {
        sb.append('"').append(escapeJsonInternal(key)).append("\":");
        if (value != null) {
            sb.append('"').append(escapeJsonInternal(value)).append('"');
        } else {
            sb.append("null");
        }
    }

    String escapeJson(String s) {
        if (s == null) return "null";
        return "\"" + escapeJsonInternal(s) + "\"";
    }

    private String escapeJsonInternal(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
