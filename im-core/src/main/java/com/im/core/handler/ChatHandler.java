package com.im.core.handler;

import com.im.api.*;
import com.im.api.content.ContentType;
import com.im.api.content.IMessageContent;
import com.im.codec.ContentSerializer;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

/**
 * 聊天消息处理器。
 *
 * 职责（Receiver 角色 — 只收不推）：
 *   ① 验证发送权限（群聊检查成员身份）
 *   ② 分配消息序号（ISequenceManager.nextSequence → _ms header）
 *   ③ 解析消息内容（_ct header → ContentSerializer）
 *   ④ 持久化到 IMessageStore（写前日志，保底不丢）
 *   ⑤ publish 到 IMessageQueue（persist + deliver）
 *   ⑥ 返回 ACK 给发送方
 *
 * 群聊展开在 DeliveryConsumer 中完成（避免 ChatHandler 感知群成员列表）。
 *
 * 消息序号：
 *   服务端为每条消息分配 conversation 级别的递增 seq，
 *   按 seq 排序显示，pullBySequence 按 seq 拉取历史。
 *
 * 流程（参考 OpenIM 的 sendMsg → MsgToMQ → 回 ACK）：
 *   ChatHandler.handle
 *     ├── ISequenceManager.nextSequence(conversationId)  // 分配 seq
 *     ├── parse + validate
 *     ├── messageStore.save(msg)                // 先存
 *     ├── mq.publishAsync("persist", msg)            // 持久化
 *     ├── mq.publishAsync("deliver", msg)            // 投递
 *     └── ctx.writeAndFlush(ACK)                // 立刻回
 */
public class ChatHandler implements IMessageHandler {

    private static final Logger log = LoggerFactory.getLogger(ChatHandler.class);

    public static final String CONTENT_TYPE_HEADER = "_ct";
    public static final String MSG_SEQ_HEADER = "_ms";

    private final IMessageQueue messageQueue;
    private final IMessageStore messageStore;
    private final ISequenceManager sequenceManager;
    private final IGroupManager groupManager;
    private final IWebhookManager webhookManager;

    public ChatHandler(IMessageQueue messageQueue, IMessageStore messageStore, ISequenceManager sequenceManager) {
        this(messageQueue, messageStore, sequenceManager, null, null);
    }

    public ChatHandler(IMessageQueue messageQueue, IMessageStore messageStore,
                       ISequenceManager sequenceManager, IGroupManager groupManager) {
        this(messageQueue, messageStore, sequenceManager, groupManager, null);
    }

    public ChatHandler(IMessageQueue messageQueue, IMessageStore messageStore,
                       ISequenceManager sequenceManager, IGroupManager groupManager,
                       IWebhookManager webhookManager) {
        this.messageQueue = messageQueue;
        this.messageStore = messageStore;
        this.sequenceManager = sequenceManager;
        this.groupManager = groupManager;
        this.webhookManager = webhookManager;
    }

    @Override
    public void handle(ChannelHandlerContext ctx, IMCommand msg) {
        // 1. 解析消息内容
        IMessageContent content = null;
        String ctRaw = msg.getHeader(CONTENT_TYPE_HEADER);
        if (ctRaw != null) {
            try {
                ContentType ct = ContentType.valueOf(ctRaw.toUpperCase());
                content = ContentSerializer.fromBytes(ct, msg.getBody());
                content.validate();
            } catch (Exception e) {
                log.warn("Invalid message content: {}", e.getMessage());
                sendError(ctx, msg, "invalid content: " + e.getMessage());
                return;
            }
        }

        // 2. 按命令类型分发
        CommandType cmd = msg.getType();
        if (cmd == CommandType.SINGLE_CHAT) {
            handleSingleChat(ctx, msg, content);
        } else if (cmd == CommandType.GROUP_CHAT) {
            handleGroupChat(ctx, msg, content);
        } else {
            sendError(ctx, msg, "unsupported command: " + cmd);
        }
    }

    private void handleSingleChat(ChannelHandlerContext ctx, IMCommand msg, IMessageContent content) {
        String toUserId = msg.getHeader("toUserId");
        if (toUserId == null) {
            sendError(ctx, msg, "toUserId is required");
            return;
        }

        logContent("SINGLE_CHAT", msg, content);

        // ① BeforeSendSingleMsg webhook（可阻断）
        if (webhookManager != null) {
            String payload = buildWebhookPayload(msg, content, null);
            if (!webhookManager.callBefore(IWebhookManager.Event.BEFORE_SEND_SINGLE_MSG, payload)) {
                sendError(ctx, msg, "blocked by before send webhook");
                return;
            }
        }

        // ② 分配 conversation 级消息序号
        String conversationId = buildConversationId(msg);
        if (conversationId != null && sequenceManager != null) {
            long seq = sequenceManager.nextSequence(conversationId);
            msg.putHeader(MSG_SEQ_HEADER, String.valueOf(seq));
            msg.putHeader("conversationId", conversationId);
        }

        // ③ 先存（写前日志，防止 MQ 消费失败丢消息）
        if (messageStore != null) {
            messageStore.save(msg);
        }

        // ④ 发布到持久化 + 投递 topic
        if (messageQueue != null) {
            messageQueue.publishAsync(MessageQueueTopics.PERSIST, msg);
            messageQueue.publishAsync(MessageQueueTopics.DELIVER, msg);
        }

        // ⑤ 回 ACK
        IMCommand ack = msg.createAcknowledgement(CommandType.SINGLE_CHAT_ACK);
        ack.putHeader("status", "RECEIVED");
        if (conversationId != null) {
            ack.putHeader("conversationId", conversationId);
            ack.putHeader(MSG_SEQ_HEADER, msg.getHeader(MSG_SEQ_HEADER));
        }
        ctx.writeAndFlush(ack);

        log.info("Msg {} queued: seq={}, to={}, conv={}",
                msg.getMessageId(), msg.getHeader(MSG_SEQ_HEADER), toUserId, conversationId);

        // ⑥ AfterSendSingleMsg webhook（异步通知）
        if (webhookManager != null) {
            String payload = buildWebhookPayload(msg, content, null);
            webhookManager.callAfterAsync(IWebhookManager.Event.AFTER_SEND_SINGLE_MSG, payload);
        }
    }

    private void handleGroupChat(ChannelHandlerContext ctx, IMCommand msg, IMessageContent content) {
        String groupId = msg.getHeader("groupId");
        String fromUserId = msg.getHeader("fromUserId");
        if (groupId == null) {
            sendError(ctx, msg, "groupId is required");
            return;
        }

        // 验证发送者是群成员
        if (groupManager != null && !groupManager.isMember(groupId, fromUserId)) {
            sendError(ctx, msg, "user is not a member of group: " + groupId);
            return;
        }

        logContent("GROUP_CHAT", msg, content);

        // ① BeforeSendGroupMsg webhook（可阻断）
        if (webhookManager != null) {
            String payload = buildWebhookPayload(msg, content, groupId);
            if (!webhookManager.callBefore(IWebhookManager.Event.BEFORE_SEND_GROUP_MSG, payload)) {
                sendError(ctx, msg, "blocked by before send webhook");
                return;
            }
        }

        // ② 分配群聊 seq
        String conversationId = "group_" + groupId;
        if (sequenceManager != null) {
            long seq = sequenceManager.nextSequence(conversationId);
            msg.putHeader(MSG_SEQ_HEADER, String.valueOf(seq));
            msg.putHeader("conversationId", conversationId);
        }

        // ③ 存
        if (messageStore != null) {
            messageStore.save(msg);
        }

        // ④ 持久化 + 投递
        if (messageQueue != null) {
            messageQueue.publishAsync(MessageQueueTopics.PERSIST, msg);
            messageQueue.publishAsync(MessageQueueTopics.DELIVER, msg);
        }

        // ⑤ 回 ACK
        IMCommand ack = msg.createAcknowledgement(CommandType.GROUP_CHAT_ACK);
        ack.putHeader("status", "RECEIVED");
        if (conversationId != null) {
            ack.putHeader("conversationId", conversationId);
            ack.putHeader(MSG_SEQ_HEADER, msg.getHeader(MSG_SEQ_HEADER));
        }
        ctx.writeAndFlush(ack);

        log.info("Group msg {} queued: seq={}, group={}",
                msg.getMessageId(), msg.getHeader(MSG_SEQ_HEADER), groupId);

        // ⑥ AfterSendGroupMsg webhook（异步通知）
        if (webhookManager != null) {
            String payload = buildWebhookPayload(msg, content, groupId);
            webhookManager.callAfterAsync(IWebhookManager.Event.AFTER_SEND_GROUP_MSG, payload);
        }
    }

    /**
     * 构造 conversation ID。
     */
    private String buildConversationId(IMCommand msg) {
        String fromUserId = msg.getHeader("fromUserId");
        String toUserId = msg.getHeader("toUserId");
        if (fromUserId != null && toUserId != null) {
            String user1 = fromUserId.compareTo(toUserId) <= 0 ? fromUserId : toUserId;
            String user2 = fromUserId.compareTo(toUserId) <= 0 ? toUserId : fromUserId;
            return "single_" + user1 + "_" + user2;
        }
        return null;
    }

    private void sendError(ChannelHandlerContext ctx, IMCommand msg, String reason) {
        IMCommand error = msg.createAcknowledgement(CommandType.ERROR);
        error.putHeader("reason", reason);
        ctx.writeAndFlush(error);
    }

    private void logContent(String tag, IMCommand msg, IMessageContent content) {
        if (content != null) {
            log.info("{} content: type={}, detail={}", tag, content.getContentType(), content);
        } else {
            log.info("{} content: none (raw body {} bytes)", tag,
                    msg.getBody() != null ? msg.getBody().length : 0);
        }
    }

    /**
     * 构建 webhook 请求的 JSON payload。
     * 格式：{"fromUserId":"...", "toUserId":"...", "groupId":"...", "contentType":"...",
     *        "content":"...", "conversationId":"...", "seq":"..."}
     */
    private String buildWebhookPayload(IMCommand msg, IMessageContent content, String groupId) {
        StringBuilder sb = new StringBuilder(256);
        sb.append('{');
        appendJsonField(sb, "fromUserId", msg.getHeader("fromUserId"));
        sb.append(',');
        appendJsonField(sb, "toUserId", msg.getHeader("toUserId"));
        if (groupId != null) {
            sb.append(',');
            appendJsonField(sb, "groupId", groupId);
        }
        sb.append(',');
        appendJsonField(sb, "contentType", msg.getHeader(CONTENT_TYPE_HEADER));
        sb.append(',');
        sb.append("\"content\":").append(escapeJson(msg.getBodyString()));
        sb.append(',');
        appendJsonField(sb, "conversationId", msg.getHeader("conversationId"));
        sb.append(',');
        appendJsonField(sb, "seq", msg.getHeader(MSG_SEQ_HEADER));
        sb.append('}');
        return sb.toString();
    }

    private void appendJsonField(StringBuilder sb, String key, String value) {
        sb.append('"').append(escapeJsonInternal(key)).append("\":");
        if (value != null) {
            sb.append('"').append(escapeJsonInternal(value)).append('"');
        } else {
            sb.append("null");
        }
    }

    private String escapeJsonInternal(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private String escapeJson(String s) {
        if (s == null) return "null";
        return "\"" + escapeJsonInternal(s) + "\"";
    }

    @Override
    public Set<CommandType> supportedTypes() {
        return Set.of(CommandType.SINGLE_CHAT, CommandType.GROUP_CHAT);
    }
}
