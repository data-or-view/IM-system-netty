package com.im.core.usecase;

import com.im.api.CommandType;
import com.im.api.IGroupManager;
import com.im.api.IMCommand;
import com.im.api.IMessageQueue;
import com.im.api.IMessageStore;
import com.im.api.ISequenceManager;
import com.im.api.MessageQueueTopics;
import com.im.api.content.IMessageContent;
import com.im.core.handler.ContentSerializer;
import com.im.core.handler.WebhookService;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

public class SendMessageUseCase {

    public static final String MSG_SEQ_HEADER = "_ms";

    private static final AtomicLong msgIdCounter = new AtomicLong(System.currentTimeMillis());

    private final IMessageQueue messageQueue;
    private final IMessageStore messageStore;
    private final ISequenceManager sequenceManager;
    private final IGroupManager groupManager;
    private final WebhookService webhookService;

    public SendMessageUseCase(IMessageQueue messageQueue, IMessageStore messageStore,
                              ISequenceManager sequenceManager, IGroupManager groupManager,
                              WebhookService webhookService) {
        this.messageQueue = messageQueue;
        this.messageStore = messageStore;
        this.sequenceManager = sequenceManager;
        this.groupManager = groupManager;
        this.webhookService = webhookService;
    }

    public record SendMessageResult(String conversationId, long seq, CommandType responseType) {}

    // ── 新接口：统一 handler 使用 ──

    /**
     * 从 ApiRequest params 处理消息发送（新）。
     *
     * @param params     业务参数 map
     * @param fromUserId 发送者
     * @param toUserId   接收者（单聊）
     * @param groupId    群 ID（群聊）
     * @param content    已解析的消息内容
     * @param uid        认证用户 ID（fromUserId 必须匹配）
     * @return 处理结果，或 null 表示被阻断
     */
    public SendMessageResult execute(Map<String, Object> params, String fromUserId,
                                     String toUserId, String groupId, IMessageContent content,
                                     String uid) {
        if (fromUserId == null || !fromUserId.equals(uid)) {
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

        // 构建临时 IMCommand 用于持久化（过渡方案）
        IMCommand msg = buildTempCommand(params, fromUserId, toUserId, null, content, conversationId, seq);
        if (messageStore != null) messageStore.save(msg);
        if (messageQueue != null) {
            messageQueue.publishAsync(MessageQueueTopics.PERSIST, msg);
            messageQueue.publishAsync(MessageQueueTopics.DELIVER, msg);
        }

        webhookService.afterSendSingle(params, fromUserId, toUserId, content);

        return new SendMessageResult(conversationId, seq, CommandType.SINGLE_CHAT_ACK);
    }

    private SendMessageResult handleGroupChat(Map<String, Object> params, String fromUserId,
                                               String groupId, IMessageContent content) {
        if (groupManager != null && !groupManager.isMember(groupId, fromUserId)) return null;

        if (!webhookService.beforeSendGroup(params, fromUserId, groupId, content)) return null;

        String conversationId = "group_" + groupId;
        long seq = 0;
        if (sequenceManager != null) {
            seq = sequenceManager.nextSequence(conversationId);
        }

        IMCommand msg = buildTempCommand(params, fromUserId, null, groupId, content, conversationId, seq);
        if (messageStore != null) messageStore.save(msg);
        if (messageQueue != null) {
            messageQueue.publishAsync(MessageQueueTopics.PERSIST, msg);
            messageQueue.publishAsync(MessageQueueTopics.DELIVER, msg);
        }

        webhookService.afterSendGroup(params, fromUserId, groupId, content);

        return new SendMessageResult(conversationId, seq, CommandType.GROUP_CHAT_ACK);
    }

    /**
     * 构建临时 IMCommand 用于持久化层桥接。
     * 下个版本移除 IMCommand 后，直接调用持久层新接口。
     */
    private IMCommand buildTempCommand(Map<String, Object> params, String fromUserId,
                                        String toUserId, String groupId, IMessageContent content,
                                        String conversationId, long seq) {
        CommandType type = groupId != null ? CommandType.GROUP_CHAT : CommandType.SINGLE_CHAT;
        IMCommand cmd = new IMCommand(type);

        // 协议字段
        String serverMid = "srv_" + msgIdCounter.incrementAndGet();
        cmd.setMessageId(serverMid);

        // headers
        cmd.putHeader("fromUserId", fromUserId);
        if (toUserId != null) cmd.putHeader("toUserId", toUserId);
        if (groupId != null) cmd.putHeader("groupId", groupId);
        cmd.putHeader("_uid", fromUserId);
        cmd.putHeader("conversationId", conversationId);
        cmd.putHeader(MSG_SEQ_HEADER, String.valueOf(seq));

        // 内容类型
        Object ctObj = params.get("_ct");
        if (ctObj != null) {
            cmd.putHeader("_ct", ctObj.toString());
        }

        // body
        if (content != null) {
            cmd.setBody(ContentSerializer.toBytes(content));
        }

        return cmd;
    }

    private static String buildConversationId(String fromUserId, String toUserId) {
        if (fromUserId != null && toUserId != null) {
            String user1 = fromUserId.compareTo(toUserId) <= 0 ? fromUserId : toUserId;
            String user2 = fromUserId.compareTo(toUserId) <= 0 ? toUserId : fromUserId;
            return "single_" + user1 + "_" + user2;
        }
        return null;
    }
}
