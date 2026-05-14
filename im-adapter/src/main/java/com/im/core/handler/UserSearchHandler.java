package com.im.core.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.im.api.CommandType;
import com.im.api.IMCommand;
import com.im.api.IMessageHandler;
import com.im.api.ImErrorCode;
import com.im.api.ImException;
import com.im.api.UserInformation;
import com.im.core.usecase.UserSearchUseCase;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;

public class UserSearchHandler implements IMessageHandler {

    private static final Logger log = LoggerFactory.getLogger(UserSearchHandler.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final UserSearchUseCase userSearchUseCase;

    public UserSearchHandler(UserSearchUseCase userSearchUseCase) {
        this.userSearchUseCase = userSearchUseCase;
    }

    @Override
    public Set<CommandType> supportedTypes() {
        return Set.of(CommandType.USER_SEARCH);
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

        List<UserInformation> users = userSearchUseCase.execute(keyword.trim(), limit);

        IMCommand ack = msg.createAcknowledgement(CommandType.USER_SEARCH_ACK);
        ack.putHeader("status", "OK");
        ack.putHeader("count", String.valueOf(users.size()));
        try {
            ack.putHeader("users", MAPPER.writeValueAsString(users));
        } catch (Exception e) {
            throw new ImException(ImErrorCode.INTERNAL_ERROR, "serialize user list failed");
        }
        ctx.writeAndFlush(ack);
        log.debug("USER_SEARCH: keyword={} count={}", keyword, users.size());
    }
}
