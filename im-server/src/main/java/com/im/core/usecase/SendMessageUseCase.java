package com.im.core.usecase;

import com.im.api.IGroupManager;
import com.im.api.IChatSendPolicy;
import com.im.api.IMessageQueue;
import com.im.api.ISequenceManager;
import com.im.api.ConversationIds;
import com.im.api.Message;
import com.im.api.MessageQueueTopics;
import com.im.api.content.IMessageContent;
import com.im.common.enums.ImErrorCode;
import com.im.common.exception.ForbiddenException;
import com.im.common.exception.InfrastructureException;
import com.im.common.exception.ValidationException;
import com.im.common.id.IdGenerator;
import com.im.common.retry.RetryExecutor;
import com.im.common.retry.RetryStrategies;
import com.im.core.handler.ContentSerializer;
import com.im.core.handler.WebhookService;
import com.im.api.BusinessMessageDlqStore;
import com.im.api.SendMessageIdempotency;
import com.im.core.retry.FailsafeRetryExecutor;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Pattern;

public class SendMessageUseCase {

    private static final Pattern CLIENT_MSG_ID_PATTERN = Pattern.compile("[A-Za-z0-9._:-]{8,64}");
    private static final String STATUS_RECEIVED = "RECEIVED";
    private static final String STATUS_RECEIVED_PENDING_DELIVERY = "RECEIVED_PENDING_DELIVERY";

    private final IMessageQueue messageQueue;
    private final ISequenceManager sequenceManager;
    private final WebhookService webhookService;
    private final IChatSendPolicy sendPolicy;
    private final RetryExecutor retryExecutor;
    private final SendMessageIdempotency sendMessageIdempotency;
    private final BusinessMessageDlqStore failureStore;

    public SendMessageUseCase(IMessageQueue messageQueue,
                              ISequenceManager sequenceManager, IGroupManager groupManager,
                              WebhookService webhookService) {
        this(messageQueue, sequenceManager, webhookService, new LegacyGroupSendPolicy(groupManager));
    }

    public SendMessageUseCase(IMessageQueue messageQueue,
                              ISequenceManager sequenceManager,
                              WebhookService webhookService,
                              IChatSendPolicy sendPolicy) {
        this(messageQueue, sequenceManager, webhookService, sendPolicy,
                new FailsafeRetryExecutor(), SendMessageIdempotency.none(), BusinessMessageDlqStore.none());
    }

    public SendMessageUseCase(IMessageQueue messageQueue,
                              ISequenceManager sequenceManager,
                              WebhookService webhookService,
                              IChatSendPolicy sendPolicy,
                              RetryExecutor retryExecutor,
                              SendMessageIdempotency sendMessageIdempotency,
                              BusinessMessageDlqStore failureStore) {
        this.messageQueue = messageQueue;
        this.sequenceManager = sequenceManager;
        this.webhookService = webhookService;
        this.sendPolicy = sendPolicy;
        this.retryExecutor = retryExecutor != null ? retryExecutor : new FailsafeRetryExecutor();
        this.sendMessageIdempotency = sendMessageIdempotency != null
                ? sendMessageIdempotency : SendMessageIdempotency.none();
        this.failureStore = failureStore != null ? failureStore : BusinessMessageDlqStore.none();
    }
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

        String conversationId = ConversationIds.single(fromUserId, toUserId);
        String clientMsgId = requireClientMsgId(params);
        String idempotencyKey = idempotencyKey(fromUserId, conversationId, clientMsgId);

        return sendMessageIdempotency.execute(idempotencyKey, () -> {
            if (sendPolicy != null) {
                sendPolicy.requireCanSendSingle(fromUserId, toUserId);
            }
            if (!webhookService.beforeSendSingle(params, fromUserId, toUserId, content)) {
                throw new ForbiddenException("message sending blocked");
            }

            SendMessageResult result = publishMessage(params, fromUserId, toUserId, null,
                    content, conversationId, clientMsgId);
            webhookService.afterSendSingle(params, fromUserId, toUserId, content);
            return result;
        }, SendMessageResult.class);
    }

    private SendMessageResult handleGroupChat(Map<String, Object> params, String fromUserId,
                                               String groupId, IMessageContent content) {
        String conversationId = ConversationIds.group(groupId);
        String clientMsgId = requireClientMsgId(params);
        String idempotencyKey = idempotencyKey(fromUserId, conversationId, clientMsgId);

        return sendMessageIdempotency.execute(idempotencyKey, () -> {
            if (sendPolicy != null) {
                sendPolicy.requireCanSendGroup(fromUserId, groupId);
            }
            if (!webhookService.beforeSendGroup(params, fromUserId, groupId, content)) {
                throw new ForbiddenException("message sending blocked");
            }

            SendMessageResult result = publishMessage(params, fromUserId, null, groupId,
                    content, conversationId, clientMsgId);
            webhookService.afterSendGroup(params, fromUserId, groupId, content);
            return result;
        }, SendMessageResult.class);
    }

    public SendMessageResult publishGroupSystem(String fromUserId, String groupId, IMessageContent content) {
        String conversationId = ConversationIds.group(groupId);
        String clientMsgId = IdGenerator.messageId();
        return publishMessage(Map.of("clientMsgId", clientMsgId), fromUserId, null, groupId,
                content, conversationId, clientMsgId);
    }

    private SendMessageResult publishMessage(Map<String, Object> params, String fromUserId,
                                             String toUserId, String groupId, IMessageContent content,
                                             String conversationId, String clientMsgId) {
        long seq = 0;
        if (sequenceManager != null) {
            seq = sequenceManager.nextSequence(conversationId);
        }

        Message msg = buildMessage(params, fromUserId, toUserId, groupId, content,
                conversationId, seq, clientMsgId);
        publishRequired(MessageQueueTopics.PERSIST, msg);

        String status = publishRecoverable(MessageQueueTopics.DELIVER, msg)
                ? STATUS_RECEIVED
                : STATUS_RECEIVED_PENDING_DELIVERY;

        return new SendMessageResult(msg.getMessageId(), conversationId, seq, status);
    }

    /**
     * 构建 Message 用于持久化层。
     */
    private Message buildMessage(Map<String, Object> params, String fromUserId,
                                  String toUserId, String groupId, IMessageContent content,
                                  String conversationId, long seq, String clientMsgId) {
        Message msg = new Message();
        msg.setMessageId(clientMsgId);
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

    private void publishRequired(String topic, Message msg) {
        try {
            publishWithRetry(topic, msg);
        } catch (RuntimeException e) {
            failureStore.recordFailure(topic, msg, e);
            throw new InfrastructureException(ImErrorCode.MQ_UNAVAILABLE,
                    "message persist publish failed after retry", e);
        }
    }

    private boolean publishRecoverable(String topic, Message msg) {
        try {
            publishWithRetry(topic, msg);
            return true;
        } catch (RuntimeException e) {
            try {
                failureStore.recordFailure(topic, msg, e);
                return false;
            } catch (RuntimeException failureRecordError) {
                throw new InfrastructureException(ImErrorCode.MQ_UNAVAILABLE,
                        "message deliver publish failed and business-DLQ record failed", failureRecordError);
            }
        }
    }

    private void publishWithRetry(String topic, Message msg) {
        if (messageQueue == null) {
            return;
        }
        retryExecutor.execute(RetryStrategies.MQ_PUBLISH, () -> {
            messageQueue.publish(topic, msg);
            return null;
        });
    }

    private static String requireClientMsgId(Map<String, Object> params) {
        Object value = params != null && params.containsKey("clientMsgId")
                ? params.get("clientMsgId")
                : params != null ? params.get("client_msg_id") : null;
        String clientMsgId = value instanceof String s ? s.trim() : "";
        if (!CLIENT_MSG_ID_PATTERN.matcher(clientMsgId).matches()) {
            throw new ValidationException("clientMsgId is required and must be 8-64 chars: letters, digits, '.', '_', ':' or '-'");
        }
        return clientMsgId;
    }

    private static String idempotencyKey(String fromUserId, String conversationId, String clientMsgId) {
        return "send:" + fromUserId + ":" + conversationId + ":" + clientMsgId;
    }

    private static final class LegacyGroupSendPolicy implements IChatSendPolicy {
        private final IGroupManager groupManager;

        private LegacyGroupSendPolicy(IGroupManager groupManager) {
            this.groupManager = groupManager;
        }

        @Override
        public void requireCanSendSingle(String fromUserId, String toUserId) {
            // Legacy constructor preserved the previous permissive single-chat behavior.
        }

        @Override
        public void requireCanSendGroup(String fromUserId, String groupId) {
            if (groupManager != null && !groupManager.isMember(groupId, fromUserId)) {
                throw new ForbiddenException("not a group member");
            }
            if (groupManager != null && groupManager.isMemberMuted(groupId, fromUserId)) {
                throw new ForbiddenException("group member muted");
            }
        }
    }

}
