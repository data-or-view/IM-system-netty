package com.im.core.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.im.api.CommandType;
import com.im.api.FriendInformation;
import com.im.api.IFriendManager;
import com.im.api.IMCommand;
import com.im.api.IMessageHandler;
import com.im.api.ImErrorCode;
import com.im.api.ImException;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;

/**
 * 好友业务处理器。
 *
 * <p>处理好友申请、审批、删除、列表查询等命令。</p>
 *
 * <h3>支持的命令码</h3>
 * <table border="1">
 *   <tr><th>命令</th><th>说明</th><th>请求头</th><th>响应头</th></tr>
 *   <tr><td>FRIEND_APPLY(70)</td><td>申请加好友</td>
 *       <td>toUserId, reqMsg</td><td>status=OK</td></tr>
 *   <tr><td>FRIEND_APPROVE(72)</td><td>审批好友申请</td>
 *       <td>fromUserId, handleMsg, agreed</td><td>status=OK</td></tr>
 *   <tr><td>FRIEND_REMOVE(74)</td><td>删除好友</td>
 *       <td>friendUserId</td><td>status=OK</td></tr>
 *   <tr><td>FRIEND_LIST(76)</td><td>获取好友列表</td>
 *       <td>（无）</td><td>friends (JSON array)</td></tr>
 * </table>
 */
public class FriendHandler implements IMessageHandler {

    private static final Logger log = LoggerFactory.getLogger(FriendHandler.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final IFriendManager friendManager;

    public FriendHandler(IFriendManager friendManager) {
        this.friendManager = friendManager;
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

        friendManager.applyAddFriend(fromUserId, toUserId, reqMsg);

        IMCommand ack = msg.createAcknowledgement(CommandType.FRIEND_APPLY_ACK);
        ack.putHeader("status", "OK");
        ctx.writeAndFlush(ack);
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

        friendManager.respondFriendApply(userId, fromUserId, handleMsg, agreed);

        IMCommand ack = msg.createAcknowledgement(CommandType.FRIEND_APPROVE_ACK);
        ack.putHeader("status", "OK");
        ctx.writeAndFlush(ack);
        log.debug("FRIEND_APPROVE: userId={} fromUserId={} agreed={}", userId, fromUserId, agreed);
    }

    private void handleRemove(ChannelHandlerContext ctx, IMCommand msg, String userId) {
        String friendUserId = msg.getHeader("friendUserId");
        if (friendUserId == null || friendUserId.isEmpty()) {
            throw new ImException(ImErrorCode.BAD_REQUEST, "missing friendUserId");
        }

        friendManager.deleteFriend(userId, friendUserId);

        IMCommand ack = msg.createAcknowledgement(CommandType.FRIEND_REMOVE_ACK);
        ack.putHeader("status", "OK");
        ctx.writeAndFlush(ack);
        log.debug("FRIEND_REMOVE: owner={} friend={}", userId, friendUserId);
    }

    private void handleList(ChannelHandlerContext ctx, IMCommand msg, String userId) {
        List<FriendInformation> friends = friendManager.getFriendList(userId);

        IMCommand ack = msg.createAcknowledgement(CommandType.FRIEND_LIST_ACK);
        ack.putHeader("status", "OK");
        ack.putHeader("count", String.valueOf(friends.size()));
        try {
            String json = MAPPER.writeValueAsString(friends);
            ack.putHeader("friends", json);
        } catch (Exception e) {
            throw new ImException(ImErrorCode.INTERNAL_ERROR, "serialize friend list failed");
        }
        ctx.writeAndFlush(ack);
        log.debug("FRIEND_LIST: userId={} count={}", userId, friends.size());
    }
}
