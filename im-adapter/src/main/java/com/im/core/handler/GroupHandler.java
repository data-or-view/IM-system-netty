package com.im.core.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.im.api.CommandType;
import com.im.api.IMCommand;
import com.im.api.IMessageHandler;
import com.im.api.ImErrorCode;
import com.im.api.ImException;
import com.im.core.usecase.GroupUseCase;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;

public class GroupHandler implements IMessageHandler {

    private static final Logger log = LoggerFactory.getLogger(GroupHandler.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final GroupUseCase groupUseCase;

    public GroupHandler(GroupUseCase groupUseCase) {
        this.groupUseCase = groupUseCase;
    }

    @Override
    public Set<CommandType> supportedTypes() {
        return Set.of(
                CommandType.GROUP_CREATE,
                CommandType.GROUP_JOIN,
                CommandType.GROUP_QUIT,
                CommandType.GROUP_KICK,
                CommandType.GROUP_INFO_UPDATE
        );
    }

    @Override
    public void handle(ChannelHandlerContext ctx, IMCommand msg) {
        String userId = msg.getHeader("userId");
        if (userId == null || userId.isEmpty()) {
            throw new ImException(ImErrorCode.BAD_REQUEST, "missing userId");
        }

        switch (msg.getType()) {
            case GROUP_CREATE -> handleCreate(ctx, msg, userId);
            case GROUP_JOIN -> handleJoin(ctx, msg, userId);
            case GROUP_QUIT -> handleQuit(ctx, msg, userId);
            case GROUP_KICK -> handleKick(ctx, msg, userId);
            case GROUP_INFO_UPDATE -> handleInfoUpdate(ctx, msg, userId);
            default -> {}
        }
    }

    private void handleCreate(ChannelHandlerContext ctx, IMCommand msg, String userId) {
        String groupId = msg.getHeader("groupId");
        String groupName = msg.getHeader("groupName");
        String faceUrl = msg.getHeader("faceUrl");
        if (groupId == null || groupId.isEmpty()) throw new ImException(ImErrorCode.BAD_REQUEST, "missing groupId");
        if (groupName == null || groupName.isEmpty()) throw new ImException(ImErrorCode.BAD_REQUEST, "missing groupName");

        int groupType = intHeader(msg, "groupType", 0);
        int needVerification = intHeader(msg, "needVerification", 0);

        List<String> members = List.of();
        String membersJson = msg.getHeader("members");
        if (membersJson != null && !membersJson.isEmpty()) {
            try {
                members = MAPPER.readValue(membersJson, List.class);
            } catch (Exception ignored) {}
        }

        groupUseCase.createGroup(groupId, userId, groupName, faceUrl, members, groupType, needVerification);
        ack(ctx, msg, CommandType.GROUP_CREATE_ACK);
        log.debug("GROUP_CREATE: groupId={}, name={}, owner={}", groupId, groupName, userId);
    }

    private void handleJoin(ChannelHandlerContext ctx, IMCommand msg, String userId) {
        String groupId = msg.getHeader("groupId");
        if (groupId == null || groupId.isEmpty()) throw new ImException(ImErrorCode.BAD_REQUEST, "missing groupId");
        String reqMsg = msg.getHeader("reqMsg");
        groupUseCase.joinGroup(groupId, userId, reqMsg);
        ack(ctx, msg, CommandType.GROUP_JOIN_ACK);
        log.debug("GROUP_JOIN: groupId={}, userId={}", groupId, userId);
    }

    private void handleQuit(ChannelHandlerContext ctx, IMCommand msg, String userId) {
        String groupId = msg.getHeader("groupId");
        if (groupId == null || groupId.isEmpty()) throw new ImException(ImErrorCode.BAD_REQUEST, "missing groupId");
        groupUseCase.quitGroup(groupId, userId);
        ack(ctx, msg, CommandType.GROUP_QUIT_ACK);
        log.debug("GROUP_QUIT: groupId={}, userId={}", groupId, userId);
    }

    private void handleKick(ChannelHandlerContext ctx, IMCommand msg, String userId) {
        String groupId = msg.getHeader("groupId");
        String targetUserId = msg.getHeader("targetUserId");
        if (groupId == null || groupId.isEmpty()) throw new ImException(ImErrorCode.BAD_REQUEST, "missing groupId");
        if (targetUserId == null || targetUserId.isEmpty()) throw new ImException(ImErrorCode.BAD_REQUEST, "missing targetUserId");
        groupUseCase.kickMember(groupId, userId, targetUserId);
        ack(ctx, msg, CommandType.GROUP_KICK_ACK);
        log.debug("GROUP_KICK: groupId={}, target={}, operator={}", groupId, targetUserId, userId);
    }

    private void handleInfoUpdate(ChannelHandlerContext ctx, IMCommand msg, String userId) {
        String groupId = msg.getHeader("groupId");
        if (groupId == null || groupId.isEmpty()) throw new ImException(ImErrorCode.BAD_REQUEST, "missing groupId");

        String groupName = msg.getHeader("groupName");
        String notification = msg.getHeader("notification");
        String introduction = msg.getHeader("introduction");
        String faceUrl = msg.getHeader("faceUrl");
        int needVerification = intHeader(msg, "needVerification", -1);
        int lookMemberInfo = intHeader(msg, "lookMemberInfo", -1);
        int applyMemberFriend = intHeader(msg, "applyMemberFriend", -1);

        groupUseCase.updateGroupInfo(groupId, groupName, notification, introduction,
                faceUrl, needVerification, lookMemberInfo, applyMemberFriend, userId);
        ack(ctx, msg, CommandType.GROUP_INFO_UPDATE_ACK);
        log.debug("GROUP_INFO_UPDATE: groupId={}", groupId);
    }

    private void ack(ChannelHandlerContext ctx, IMCommand msg, CommandType ackType) {
        IMCommand ack = msg.createAcknowledgement(ackType);
        ack.putHeader("status", "OK");
        ctx.writeAndFlush(ack);
    }

    private static int intHeader(IMCommand msg, String key, int fallback) {
        String v = msg.getHeader(key);
        if (v == null || v.isEmpty()) return fallback;
        try { return Integer.parseInt(v); } catch (NumberFormatException e) { return fallback; }
    }
}
