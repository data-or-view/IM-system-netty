package com.im.core.handler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.im.api.CommandType;
import com.im.api.IMCommand;
import com.im.api.IMessageHandler;
import com.im.core.usecase.PullMessageUseCase;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class PullMessageHandler implements IMessageHandler {

    private static final Logger log = LoggerFactory.getLogger(PullMessageHandler.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final PullMessageUseCase pullMessageUseCase;

    public PullMessageHandler(PullMessageUseCase pullMessageUseCase) {
        this.pullMessageUseCase = pullMessageUseCase;
    }

    @Override
    public void handle(ChannelHandlerContext ctx, IMCommand msg) {
        String conversationId = msg.getHeader("conversationId");
        if (conversationId == null || conversationId.isBlank()) {
            sendError(ctx, msg, "conversationId is required");
            return;
        }

        long startSeq = parseLong(msg.getHeader("_ms_start"), 0);
        long endSeq = parseLong(msg.getHeader("_ms_end"), 0);
        int limit = (int) parseLong(msg.getHeader("limit"), 50);

        PullMessageUseCase.PullMessageResult result = pullMessageUseCase.execute(conversationId, startSeq, endSeq, limit);

        IMCommand ack = msg.createAcknowledgement(CommandType.PULL_MESSAGE_ACK);
        ack.putHeader("conversationId", conversationId);
        ack.putHeader("_count", String.valueOf(result.messages().size()));
        ack.putHeader("_max_seq", String.valueOf(result.maxSeq()));

        try {
            List<Map<String, Object>> msgMaps = result.messages().stream()
                    .map(IMCommand::toJsonMap)
                    .collect(Collectors.toList());
            ack.setBody(MAPPER.writeValueAsBytes(msgMaps));
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize pull response", e);
            ack.setBody("[]".getBytes(StandardCharsets.UTF_8));
        }

        ctx.writeAndFlush(ack);
        log.info("Pulled {} messages for conversation {}", result.messages().size(), conversationId);
    }

    private void sendError(ChannelHandlerContext ctx, IMCommand msg, String reason) {
        IMCommand error = msg.createAcknowledgement(CommandType.ERROR);
        error.putHeader("reason", reason);
        ctx.writeAndFlush(error);
    }

    private long parseLong(String s, long defaultValue) {
        if (s != null) {
            try { return Long.parseLong(s); } catch (NumberFormatException ignored) {}
        }
        return defaultValue;
    }

    @Override
    public Set<CommandType> supportedTypes() {
        return Set.of(CommandType.PULL_MESSAGE);
    }
}
