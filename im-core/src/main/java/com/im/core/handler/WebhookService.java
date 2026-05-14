package com.im.core.handler;

import com.im.api.IMCommand;
import com.im.api.IWebhookManager;
import com.im.api.content.IMessageContent;

/**
 * Webhook 调用服务。
 *
 * <p>封装消息发送前后的 webhook 调用逻辑，包括：</p>
 * <ul>
 *   <li>同步 webhook（发送前）：返回 false 可阻断消息发送</li>
 *   <li>异步 webhook（发送后）：纯通知，不阻塞主流程</li>
 *   <li>Webhook payload JSON 构建</li>
 * </ul>
 *
 * <p>从 {@link ChatHandler} 中提取，使 webhook 逻辑可独立测试和替换。</p>
 */
public class WebhookService {

    private final IWebhookManager webhookManager;

    public WebhookService(IWebhookManager webhookManager) {
        this.webhookManager = webhookManager;
    }

    /** 单聊发送前 webhook（可通过返回 false 阻断）。 */
    public boolean beforeSendSingle(IMCommand msg, IMessageContent content) {
        if (webhookManager == null) return true;
        String payload = buildPayload(msg, content, null);
        return webhookManager.callBefore(IWebhookManager.Event.BEFORE_SEND_SINGLE_MSG, payload);
    }

    /** 群聊发送前 webhook（可通过返回 false 阻断）。 */
    public boolean beforeSendGroup(IMCommand msg, IMessageContent content, String groupId) {
        if (webhookManager == null) return true;
        String payload = buildPayload(msg, content, groupId);
        return webhookManager.callBefore(IWebhookManager.Event.BEFORE_SEND_GROUP_MSG, payload);
    }

    /** 单聊发送后 webhook（异步通知）。 */
    public void afterSendSingle(IMCommand msg, IMessageContent content) {
        if (webhookManager == null) return;
        String payload = buildPayload(msg, content, null);
        webhookManager.callAfterAsync(IWebhookManager.Event.AFTER_SEND_SINGLE_MSG, payload);
    }

    /** 群聊发送后 webhook（异步通知）。 */
    public void afterSendGroup(IMCommand msg, IMessageContent content, String groupId) {
        if (webhookManager == null) return;
        String payload = buildPayload(msg, content, groupId);
        webhookManager.callAfterAsync(IWebhookManager.Event.AFTER_SEND_GROUP_MSG, payload);
    }

    // ── Payload 构建 ──

    /**
     * 构建 webhook 请求的 JSON payload。
     * 格式：{"fromUserId":"...", "toUserId":"...", "groupId":"...", "contentType":"...",
     *        "content":"...", "conversationId":"...", "seq":"..."}
     */
    String buildPayload(IMCommand msg, IMessageContent content, String groupId) {
        StringBuilder sb = new StringBuilder(256);
        sb.append('{');
        appendField(sb, "fromUserId", msg.getHeader("fromUserId"));
        sb.append(',');
        appendField(sb, "toUserId", msg.getHeader("toUserId"));
        if (groupId != null) {
            sb.append(',');
            appendField(sb, "groupId", groupId);
        }
        sb.append(',');
        appendField(sb, "contentType", msg.getHeader("_ct"));
        sb.append(',');
        sb.append("\"content\":").append(escapeJson(msg.getBodyString()));
        sb.append(',');
        appendField(sb, "conversationId", msg.getHeader("conversationId"));
        sb.append(',');
        appendField(sb, "seq", msg.getHeader("_ms"));
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
