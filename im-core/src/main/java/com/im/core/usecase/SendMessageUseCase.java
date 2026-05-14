package com.im.core.usecase;

import com.im.api.CommandType;
import com.im.api.IGroupManager;
import com.im.api.IMCommand;
import com.im.api.IMessageQueue;
import com.im.api.IMessageStore;
import com.im.api.ISequenceManager;
import com.im.api.MessageQueueTopics;
import com.im.api.content.IMessageContent;
import com.im.core.handler.ContentParser;
import com.im.core.handler.WebhookService;

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

    /**
     * 验证并处理消息发送。
     *
     * @return 处理结果，或 {@code null} 表示被阻断（webhook 拒绝等）
     */
    public SendMessageResult execute(IMCommand msg) {
        String fromUserId = msg.getHeader("fromUserId");
        String uid = msg.getHeader("_uid");
        if (fromUserId == null || !fromUserId.equals(uid)) {
            return null;
        }

        if (msg.getMessageId() == null || msg.getMessageId().isEmpty()) {
            String serverMid = "srv_" + msgIdCounter.incrementAndGet();
            msg.setMessageId(serverMid);
        }

        IMessageContent content;
        try {
            content = ContentParser.parse(msg);
        } catch (Exception e) {
            return null;
        }

        CommandType cmd = msg.getType();
        if (cmd == CommandType.SINGLE_CHAT) {
            return handleSingleChat(msg, content);
        } else if (cmd == CommandType.GROUP_CHAT) {
            return handleGroupChat(msg, content);
        }
        return null;
    }

    private SendMessageResult handleSingleChat(IMCommand msg, IMessageContent content) {
        String toUserId = msg.getHeader("toUserId");
        if (toUserId == null) return null;

        if (!webhookService.beforeSendSingle(msg, content)) return null;

        String conversationId = buildConversationId(msg);
        if (conversationId != null && sequenceManager != null) {
            long seq = sequenceManager.nextSequence(conversationId);
            msg.putHeader(MSG_SEQ_HEADER, String.valueOf(seq));
            msg.putHeader("conversationId", conversationId);
        }

        if (messageStore != null) messageStore.save(msg);
        if (messageQueue != null) {
            messageQueue.publishAsync(MessageQueueTopics.PERSIST, msg);
            messageQueue.publishAsync(MessageQueueTopics.DELIVER, msg);
        }

        webhookService.afterSendSingle(msg, content);

        long seq = 0;
        String seqStr = msg.getHeader(MSG_SEQ_HEADER);
        if (seqStr != null) try { seq = Long.parseLong(seqStr); } catch (NumberFormatException ignored) {}

        return new SendMessageResult(conversationId, seq, CommandType.SINGLE_CHAT_ACK);
    }

    private SendMessageResult handleGroupChat(IMCommand msg, IMessageContent content) {
        String groupId = msg.getHeader("groupId");
        String fromUserId = msg.getHeader("fromUserId");
        if (groupId == null) return null;

        if (groupManager != null && !groupManager.isMember(groupId, fromUserId)) return null;

        if (!webhookService.beforeSendGroup(msg, content, groupId)) return null;

        String conversationId = "group_" + groupId;
        if (sequenceManager != null) {
            long seq = sequenceManager.nextSequence(conversationId);
            msg.putHeader(MSG_SEQ_HEADER, String.valueOf(seq));
            msg.putHeader("conversationId", conversationId);
        }

        if (messageStore != null) messageStore.save(msg);
        if (messageQueue != null) {
            messageQueue.publishAsync(MessageQueueTopics.PERSIST, msg);
            messageQueue.publishAsync(MessageQueueTopics.DELIVER, msg);
        }

        webhookService.afterSendGroup(msg, content, groupId);

        long seq = 0;
        String seqStr = msg.getHeader(MSG_SEQ_HEADER);
        if (seqStr != null) try { seq = Long.parseLong(seqStr); } catch (NumberFormatException ignored) {}

        return new SendMessageResult(conversationId, seq, CommandType.GROUP_CHAT_ACK);
    }

    private static String buildConversationId(IMCommand msg) {
        String fromUserId = msg.getHeader("fromUserId");
        String toUserId = msg.getHeader("toUserId");
        if (fromUserId != null && toUserId != null) {
            String user1 = fromUserId.compareTo(toUserId) <= 0 ? fromUserId : toUserId;
            String user2 = fromUserId.compareTo(toUserId) <= 0 ? toUserId : fromUserId;
            return "single_" + user1 + "_" + user2;
        }
        return null;
    }
}
