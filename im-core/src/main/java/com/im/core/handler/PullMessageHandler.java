package com.im.core.handler;

import com.im.api.CommandType;
import com.im.api.IMCommand;
import com.im.api.IMessageHandler;
import com.im.api.IMessageStore;
import com.im.api.ISequenceManager;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 消息拉取处理器。
 *
 * 对应 OpenIM 的 PullMessageBySeqs / GetMaxSeq：
 *   ① PULL_MESSAGE 请求带 _ms_start / _ms_end / conversationId 头
 *   ② 从 IMessageStore 拉取匹配 seq 范围的消息
 *   ③ 返回 PULL_MESSAGE_ACK（多条消息序列化为 JSON body）
 *
 * 请求（PULL_MESSAGE）：
 *   HEADERS:  { "conversationId": "...", "_ms_start": "100", "_ms_end": "200", "limit": "50" }
 * 响应（PULL_MESSAGE_ACK）：
 *   HEADERS:  { "conversationId": "...", "_count": "10", "_max_seq": "200" }
 *   BODY:     JSON array of signed IMCommand bytes
 */
public class PullMessageHandler implements IMessageHandler {

    private static final Logger log = LoggerFactory.getLogger(PullMessageHandler.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final IMessageStore messageStore;
    private final ISequenceManager sequenceManager;

    public PullMessageHandler(IMessageStore messageStore, ISequenceManager sequenceManager) {
        this.messageStore = messageStore;
        this.sequenceManager = sequenceManager;
    }

    @Override
    public void handle(ChannelHandlerContext ctx, IMCommand msg) {
        String conversationId = msg.getHeader("conversationId");
        if (conversationId == null || conversationId.isBlank()) {
            sendError(ctx, msg, "conversationId is required");
            return;
        }

        // 解析 seq 范围
        long startSeq = parseLong(msg.getHeader("_ms_start"), 0);
        long endSeq = parseLong(msg.getHeader("_ms_end"), 0);
        int limit = (int) parseLong(msg.getHeader("limit"), 50);

        log.info("PullMessage: conv={}, start={}, end={}, limit={}",
                conversationId, startSeq, endSeq, limit);

        // 从 store 拉取
        List<IMCommand> messages = messageStore.pullBySequence(conversationId, startSeq, endSeq, limit);

        // 构建响应
        IMCommand ack = msg.createAcknowledgement(CommandType.PULL_MESSAGE_ACK);
        ack.putHeader("conversationId", conversationId);
        ack.putHeader("_count", String.valueOf(messages.size()));
        ack.putHeader("_max_seq", String.valueOf(sequenceManager.getMaximumSequence(conversationId)));

        // 编码消息列表到 body (JSON array of message maps)
        try {
            List<Map<String, Object>> msgMaps = messages.stream()
                    .map(IMCommand::toJsonMap)
                    .collect(Collectors.toList());
            byte[] body = MAPPER.writeValueAsBytes(msgMaps);
            ack.setBody(body);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize pull response", e);
            ack.setBody("[]".getBytes(StandardCharsets.UTF_8));
        }

        ctx.writeAndFlush(ack);

        log.info("Pulled {} messages for conversation {}", messages.size(), conversationId);
    }

    private void sendError(ChannelHandlerContext ctx, IMCommand msg, String reason) {
        IMCommand error = msg.createAcknowledgement(CommandType.ERROR);
        error.putHeader("reason", reason);
        ctx.writeAndFlush(error);
    }

    private long parseLong(String s, long defaultValue) {
        if (s != null) {
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException ignored) {
            }
        }
        return defaultValue;
    }

    @Override
    public Set<CommandType> supportedTypes() {
        return Set.of(CommandType.PULL_MESSAGE);
    }
}
