package com.im.core.usecase;

import com.im.api.IGroupManager;
import com.im.api.IMessageQueue;
import com.im.api.ISequenceManager;
import com.im.api.Message;
import com.im.api.MessageQueueTopics;
import com.im.api.content.IMessageContent;
import com.im.core.handler.ContentSerializer;
import com.im.core.handler.WebhookService;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

public class SendMessageUseCase {

    private static final AtomicLong msgIdCounter = new AtomicLong(System.currentTimeMillis());

    private final IMessageQueue messageQueue;
    private final ISequenceManager sequenceManager;
    private final IGroupManager groupManager;
    private final WebhookService webhookService;

    public SendMessageUseCase(IMessageQueue messageQueue,
                              ISequenceManager sequenceManager, IGroupManager groupManager,
                              WebhookService webhookService) {
        this.messageQueue = messageQueue;
        this.sequenceManager = sequenceManager;
        this.groupManager = groupManager;
        this.webhookService = webhookService;
    }

    public record SendMessageResult(String conversationId, long seq, String responseType) {}

    // ── 新接口：统一 handler 使用 ──

    /**
     * 从 ApiRequest params 处理消息发送。
     *
     * @param params   业务参数 map
     * @param fromUserId 发送者（已认证，来自 token）
     * @param toUserId 接收者（单聊）
     * @param groupId  群 ID（群聊）
     * @param content  已解析的消息内容
     * @return 处理结果，或 null 表示被阻断
     */
    public SendMessageResult execute(Map<String, Object> params, String fromUserId,
                                     String toUserId, String groupId, IMessageContent content) {
        if (fromUserId == null) {
            return null;
        }

        if (groupId != null) {
            return handleGroupChat(params, fromUserId, groupId, content);
        }
        return handleSingleChat(params, fromUserId, toUserId, content);
    }

    private SendMessageResult handleSingleChat(Map<String, Object> params, String fromUserId,
                                                String toUserId, IMessageContent content) {
        if (toUserId == null) return null;

        if (!webhookService.beforeSendSingle(params, fromUserId, toUserId, content)) return null;

        String conversationId = buildConversationId(fromUserId, toUserId);
        long seq = 0;
        if (conversationId != null && sequenceManager != null) {
            seq = sequenceManager.nextSequence(conversationId);
        }

        // 构建 Message 用于持久化
        Message msg = buildMessage(params, fromUserId, toUserId, null, content, conversationId, seq);
        if (messageQueue != null) {
            messageQueue.publishAsync(MessageQueueTopics.PERSIST, msg);
            messageQueue.publishAsync(MessageQueueTopics.DELIVER, msg);
        }

        webhookService.afterSendSingle(params, fromUserId, toUserId, content);

        return new SendMessageResult(conversationId, seq, "SINGLE_CHAT_ACK");
    }

    private SendMessageResult handleGroupChat(Map<String, Object> params, String fromUserId,
                                               String groupId, IMessageContent content) {
        if (groupManager != null && !groupManager.isMember(groupId, fromUserId)) return null;
        if (groupManager != null && groupManager.isMemberMuted(groupId, fromUserId)) return null;

        if (!webhookService.beforeSendGroup(params, fromUserId, groupId, content)) return null;

        String conversationId = "group_" + groupId;
        long seq = 0;
        if (sequenceManager != null) {
            seq = sequenceManager.nextSequence(conversationId);
        }

        Message msg = buildMessage(params, fromUserId, null, groupId, content, conversationId, seq);
        if (messageQueue != null) {
            messageQueue.publishAsync(MessageQueueTopics.PERSIST, msg);
            messageQueue.publishAsync(MessageQueueTopics.DELIVER, msg);
        }

        webhookService.afterSendGroup(params, fromUserId, groupId, content);

        return new SendMessageResult(conversationId, seq, "GROUP_CHAT_ACK");
    }

    /**
     * 构建 Message 用于持久化层。
     */
    private Message buildMessage(Map<String, Object> params, String fromUserId,
                                  String toUserId, String groupId, IMessageContent content,
                                  String conversationId, long seq) {
        Message msg = new Message();
        String serverMid = "srv_" + msgIdCounter.incrementAndGet();
        msg.setMessageId(serverMid);
        msg.setFromUserId(fromUserId);
        msg.setToUserId(toUserId);
        msg.setGroupId(groupId);
        msg.setConversationId(conversationId);
        msg.setMessageSeq(seq);
        msg.setTimestamp(System.currentTimeMillis());

        // 内容类型
        if (content != null) {
            msg.setContentType(content.getContentType().getId());
            byte[] bytes = ContentSerializer.toBytes(content);
            msg.setBody(bytes);
            // content 字段给 MessageEncoder 推送客户端用
            msg.setContent(bytes != null && bytes.length > 0
                    ? new String(bytes, StandardCharsets.UTF_8) : null);
        }

        return msg;
    }

    // 字典序拼接保证 Alice→Bob 和 Bob→Alice 共享同一 conversationId。
    // 与 PersistenceConsumer.buildConversationId 保持同步，修改须两处一起改。
    private static String buildConversationId(String a, String b) {
        if (a == null || b == null) return null;
        if (a.compareTo(b) <= 0) {
            return "single_" + a + "_" + b;
        } else {
            return "single_" + b + "_" + a;
        }
    }
}
