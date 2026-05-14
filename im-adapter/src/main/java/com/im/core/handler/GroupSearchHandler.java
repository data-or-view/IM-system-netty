package com.im.core.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.im.api.CommandType;
import com.im.api.GroupInformation;
import com.im.api.IGroupManager;
import com.im.api.IMCommand;
import com.im.api.IMessageHandler;
import com.im.api.ImErrorCode;
import com.im.api.ImException;
import com.im.core.usecase.GroupSearchUseCase;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;

public class GroupSearchHandler implements IMessageHandler {

    private static final Logger log = LoggerFactory.getLogger(GroupSearchHandler.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final GroupSearchUseCase groupSearchUseCase;

    public GroupSearchHandler(GroupSearchUseCase groupSearchUseCase) {
        this.groupSearchUseCase = groupSearchUseCase;
    }

    @Override
    public Set<CommandType> supportedTypes() {
        return Set.of(CommandType.GROUP_SEARCH);
    }

    @Override
    public void handle(ChannelHandlerContext ctx, IMCommand msg) {
        String keyword = msg.getHeader("keyword");
        if (keyword == null || keyword.trim().isEmpty()) {
            throw new ImException(ImErrorCode.BAD_REQUEST, "missing keyword");
        }

        int limit = 20;
        String limitStr = msg.getHeader("limit");
        if (limitStr != null && !limitStr.isEmpty()) {
            try { limit = Integer.parseInt(limitStr); } catch (NumberFormatException ignored) {}
        }

        List<GroupInformation> groups = groupSearchUseCase.execute(keyword.trim(), limit);

        IMCommand ack = msg.createAcknowledgement(CommandType.GROUP_SEARCH_ACK);
        ack.putHeader("status", "OK");
        ack.putHeader("count", String.valueOf(groups.size()));
        try {
            ack.putHeader("groups", MAPPER.writeValueAsString(groups));
        } catch (Exception e) {
            throw new ImException(ImErrorCode.INTERNAL_ERROR, "serialize group list failed");
        }
        ctx.writeAndFlush(ack);
        log.debug("GROUP_SEARCH: keyword={} count={}", keyword, groups.size());
    }
}
