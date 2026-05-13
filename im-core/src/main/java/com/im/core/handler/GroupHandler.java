package com.im.core.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.im.api.CommandType;
import com.im.api.GroupInformation;
import com.im.api.GroupMemberInformation;
import com.im.api.IGroupManager;
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
 * 群组业务处理器。
 *
 * <p>处理群创建、加群、退群、踢人、修改群信息等命令。</p>
 *
 * <h3>支持的请求头</h3>
 * <table>
 *   <tr><th>命令</th><th>请求头</th><th>响应头</th></tr>
 *   <tr><td>GROUP_CREATE</td>
 *       <td>groupId, groupName, faceUrl, groupType, needVerification, members(JSON)</td>
 *       <td>status=OK</td></tr>
 *   <tr><td>GROUP_JOIN</td>
 *       <td>groupId, reqMsg</td>
 *       <td>status=OK</td></tr>
 *   <tr><td>GROUP_QUIT</td>
 *       <td>groupId</td>
 *       <td>status=OK</td></tr>
 *   <tr><td>GROUP_KICK</td>
 *       <td>groupId, targetUserId</td>
 *       <td>status=OK</td></tr>
 *   <tr><td>GROUP_INFO_UPDATE</td>
 *       <td>groupId, groupName, notification, etc.</td>
 *       <td>status=OK</td></tr>
 * </table>
 */
public class GroupHandler implements IMessageHandler {

    private static final Logger log = LoggerFactory.getLogger(GroupHandler.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final IGroupManager groupManager;

    public GroupHandler(IGroupManager groupManager) {
        this.groupManager = groupManager;
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
        int needVerification = intHeader(msg, "needVerification", 1);

        // 解析 members JSON
        List<String> members = List.of();
        String membersJson = msg.getHeader("members");
        if (membersJson != null && !membersJson.isEmpty()) {
            try {
                members = MAPPER.readValue(membersJson, List.class);
            } catch (Exception ignored) {}
        }

        groupManager.createGroup(groupId, userId, groupName, faceUrl, members, groupType, needVerification);

        IMCommand ack = msg.createAcknowledgement(CommandType.GROUP_CREATE_ACK);
        ack.putHeader("status", "OK");
        ctx.writeAndFlush(ack);
        log.debug("GROUP_CREATE: groupId={}, name={}, owner={}", groupId, groupName, userId);
    }

    private void handleJoin(ChannelHandlerContext ctx, IMCommand msg, String userId) {
        String groupId = msg.getHeader("groupId");
        if (groupId == null || groupId.isEmpty()) throw new ImException(ImErrorCode.BAD_REQUEST, "missing groupId");

        String reqMsg = msg.getHeader("reqMsg");
        groupManager.joinGroup(groupId, userId, reqMsg);

        IMCommand ack = msg.createAcknowledgement(CommandType.GROUP_JOIN_ACK);
        ack.putHeader("status", "OK");
        ctx.writeAndFlush(ack);
        log.debug("GROUP_JOIN: groupId={}, userId={}", groupId, userId);
    }

    private void handleQuit(ChannelHandlerContext ctx, IMCommand msg, String userId) {
        String groupId = msg.getHeader("groupId");
        if (groupId == null || groupId.isEmpty()) throw new ImException(ImErrorCode.BAD_REQUEST, "missing groupId");

        groupManager.quitGroup(groupId, userId);

        IMCommand ack = msg.createAcknowledgement(CommandType.GROUP_QUIT_ACK);
        ack.putHeader("status", "OK");
        ctx.writeAndFlush(ack);
        log.debug("GROUP_QUIT: groupId={}, userId={}", groupId, userId);
    }

    private void handleKick(ChannelHandlerContext ctx, IMCommand msg, String userId) {
        String groupId = msg.getHeader("groupId");
        String targetUserId = msg.getHeader("targetUserId");
        if (groupId == null || groupId.isEmpty()) throw new ImException(ImErrorCode.BAD_REQUEST, "missing groupId");
        if (targetUserId == null || targetUserId.isEmpty()) throw new ImException(ImErrorCode.BAD_REQUEST, "missing targetUserId");

        groupManager.kickMember(groupId, userId, targetUserId);

        IMCommand ack = msg.createAcknowledgement(CommandType.GROUP_KICK_ACK);
        ack.putHeader("status", "OK");
        ctx.writeAndFlush(ack);
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

        groupManager.setGroupInformation(groupId, groupName, notification, introduction,
                faceUrl, needVerification, lookMemberInfo, applyMemberFriend, userId);

        IMCommand ack = msg.createAcknowledgement(CommandType.GROUP_INFO_UPDATE_ACK);
        ack.putHeader("status", "OK");
        ctx.writeAndFlush(ack);
        log.debug("GROUP_INFO_UPDATE: groupId={}", groupId);
    }

    private static int intHeader(IMCommand msg, String key, int fallback) {
        String v = msg.getHeader(key);
        if (v == null || v.isEmpty()) return fallback;
        try { return Integer.parseInt(v); } catch (NumberFormatException e) { return fallback; }
    }
}
