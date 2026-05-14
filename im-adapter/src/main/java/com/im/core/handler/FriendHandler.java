package com.im.core.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.im.api.CommandType;
import com.im.api.FriendInformation;
import com.im.api.IMCommand;
import com.im.api.IMessageHandler;
import com.im.api.ImErrorCode;
import com.im.api.ImException;
import com.im.core.usecase.FriendUseCase;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;

public class FriendHandler implements IMessageHandler {

    private static final Logger log = LoggerFactory.getLogger(FriendHandler.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final FriendUseCase friendUseCase;

    public FriendHandler(FriendUseCase friendUseCase) {
        this.friendUseCase = friendUseCase;
    }

    @Override
    public Set<CommandType> supportedTypes() {
        return Set.of(
                CommandType.FRIEND_APPLY,
                CommandType.FRIEND_APPROVE,
                CommandType.FRIEND_REMOVE,
                CommandType.FRIEND_LIST
        );
    }

    @Override
    public void handle(ChannelHandlerContext ctx, IMCommand msg) {
        String userId = msg.getHeader("userId");
        if (userId == null || userId.isEmpty()) {
            throw new ImException(ImErrorCode.BAD_REQUEST, "missing userId");
        }

        switch (msg.getType()) {
            case FRIEND_APPLY -> handleApply(ctx, msg, userId);
            case FRIEND_APPROVE -> handleApprove(ctx, msg, userId);
            case FRIEND_REMOVE -> handleRemove(ctx, msg, userId);
            case FRIEND_LIST -> handleList(ctx, msg, userId);
            default -> {}
        }
    }

    private void handleApply(ChannelHandlerContext ctx, IMCommand msg, String fromUserId) {
        String toUserId = msg.getHeader("toUserId");
        String reqMsg = msg.getHeader("reqMsg");
        if (toUserId == null || toUserId.isEmpty()) {
            throw new ImException(ImErrorCode.BAD_REQUEST, "missing toUserId");
        }
        friendUseCase.applyAddFriend(fromUserId, toUserId, reqMsg);
        ack(ctx, msg, CommandType.FRIEND_APPLY_ACK);
        log.debug("FRIEND_APPLY: {} -> {}", fromUserId, toUserId);
    }

    private void handleApprove(ChannelHandlerContext ctx, IMCommand msg, String userId) {
        String fromUserId = msg.getHeader("fromUserId");
        String handleMsg = msg.getHeader("handleMsg");
        String agreedStr = msg.getHeader("agreed");
        if (fromUserId == null || fromUserId.isEmpty()) {
            throw new ImException(ImErrorCode.BAD_REQUEST, "missing fromUserId");
        }
        boolean agreed = "true".equalsIgnoreCase(agreedStr);
        friendUseCase.respondFriendApply(userId, fromUserId, handleMsg, agreed);
        ack(ctx, msg, CommandType.FRIEND_APPROVE_ACK);
        log.debug("FRIEND_APPROVE: userId={} fromUserId={} agreed={}", userId, fromUserId, agreed);
    }

    private void handleRemove(ChannelHandlerContext ctx, IMCommand msg, String userId) {
        String friendUserId = msg.getHeader("friendUserId");
        if (friendUserId == null || friendUserId.isEmpty()) {
            throw new ImException(ImErrorCode.BAD_REQUEST, "missing friendUserId");
        }
        friendUseCase.deleteFriend(userId, friendUserId);
        ack(ctx, msg, CommandType.FRIEND_REMOVE_ACK);
        log.debug("FRIEND_REMOVE: owner={} friend={}", userId, friendUserId);
    }

    private void handleList(ChannelHandlerContext ctx, IMCommand msg, String userId) {
        List<FriendInformation> friends = friendUseCase.getFriendList(userId);
        IMCommand ack = msg.createAcknowledgement(CommandType.FRIEND_LIST_ACK);
        ack.putHeader("status", "OK");
        ack.putHeader("count", String.valueOf(friends.size()));
        try {
            ack.putHeader("friends", MAPPER.writeValueAsString(friends));
        } catch (Exception e) {
            throw new ImException(ImErrorCode.INTERNAL_ERROR, "serialize friend list failed");
        }
        ctx.writeAndFlush(ack);
        log.debug("FRIEND_LIST: userId={} count={}", userId, friends.size());
    }

    private void ack(ChannelHandlerContext ctx, IMCommand msg, CommandType ackType) {
        IMCommand ack = msg.createAcknowledgement(ackType);
        ack.putHeader("status", "OK");
        ctx.writeAndFlush(ack);
    }
}
