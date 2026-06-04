package com.im.core.handler.unified;

import com.im.api.ApiRequest;
import com.im.api.GroupInformation;
import com.im.api.GroupMemberInformation;
import com.im.api.IGroupManager;
import com.im.api.RequestHandler;
import com.im.common.exception.UnauthorizedException;
import com.im.common.exception.ValidationException;
import com.im.common.exception.NotFoundException;

import java.util.List;
import java.util.Map;

/**
 * 群组域 handler：创建、加入、退出、踢人、解散、搜索、成员列表。
 *
 * <p>合并 WS {@code GroupHandler} + HTTP {@code GroupRestHandler}。</p>
 */
@SuppressWarnings("unchecked")
public class GroupHandler implements RequestHandler {

    private final IGroupManager groupManager;

    public GroupHandler(IGroupManager groupManager) {
        this.groupManager = groupManager;
    }

    @Override
    public Object handle(ApiRequest req) {
        return switch (req.operation()) {
            case "group.create" -> handleCreate(req);
            case "group.join" -> handleJoin(req);
            case "group.quit" -> handleQuit(req);
            case "group.kick" -> handleKick(req);
            case "group.disband" -> handleDisband(req);
            case "group.info.update" -> handleInfoUpdate(req);
            case "group.info" -> handleInfo(req);
            case "group.search" -> handleSearch(req);
            case "group.members" -> handleMembers(req);
            case "group.mute_all" -> handleMuteAll(req);
            default -> throw new NotFoundException("unsupported: " + req.operation());
        };
    }

    private Object handleCreate(ApiRequest req) {
        String groupId = req.getString("groupId");
        String groupName = req.getString("groupName");
        String ownerId = req.currentUserId();
        if (ownerId == null) throw new UnauthorizedException("not authenticated");
        if (groupId == null || groupName == null) {
            throw new ValidationException("groupId and groupName are required");
        }
        String faceUrl = req.getString("faceUrl", "");
        int groupType = req.getInt("groupType", 0);
        int needVerification = req.getInt("needVerification", 0);
        List<String> members = (List<String>) req.param("members");
        if (members == null) members = List.of();

        groupManager.createGroup(groupId, ownerId, groupName, faceUrl, members, groupType, needVerification);
        return Map.of("groupId", groupId, "status", "OK");
    }

    private Object handleJoin(ApiRequest req) {
        String groupId = req.getString("groupId");
        String userId = req.currentUserId();
        if (userId == null) throw new UnauthorizedException("not authenticated");
        if (groupId == null) throw new ValidationException("groupId is required");
        String reqMsg = req.getString("reqMsg", "");
        groupManager.joinGroup(groupId, userId, reqMsg);
        return Map.of("status", "OK");
    }

    private Object handleQuit(ApiRequest req) {
        String groupId = req.getString("groupId");
        String userId = req.currentUserId();
        if (userId == null) throw new UnauthorizedException("not authenticated");
        if (groupId == null) throw new ValidationException("groupId is required");
        groupManager.quitGroup(groupId, userId);
        return Map.of("status", "OK");
    }

    private Object handleKick(ApiRequest req) {
        String groupId = req.getString("groupId");
        String operatorId = req.currentUserId();
        String targetUserId = req.getString("targetUserId");
        if (operatorId == null) throw new UnauthorizedException("not authenticated");
        if (groupId == null || targetUserId == null) {
            throw new ValidationException("groupId and targetUserId are required");
        }
        groupManager.kickMember(groupId, operatorId, targetUserId);
        return Map.of("status", "OK");
    }

    private Object handleDisband(ApiRequest req) {
        String groupId = req.getString("groupId");
        String operatorId = req.currentUserId();
        if (operatorId == null) throw new UnauthorizedException("not authenticated");
        if (groupId == null) throw new ValidationException("groupId is required");
        groupManager.disbandGroup(groupId, operatorId);
        return Map.of("status", "OK");
    }

    private Object handleInfoUpdate(ApiRequest req) {
        String groupId = req.getString("groupId");
        if (groupId == null) throw new ValidationException("groupId is required");
        groupManager.setGroupInformation(groupId,
                req.getString("groupName"), req.getString("notification"),
                req.getString("introduction"), req.getString("faceUrl"),
                req.getInt("needVerification", -1),
                req.getInt("lookMemberInfo", -1),
                req.getInt("applyMemberFriend", -1),
                req.currentUserId());
        return Map.of("status", "OK");
    }

    private Object handleInfo(ApiRequest req) {
        String groupId = req.getString("groupId");
        if (groupId == null) throw new ValidationException("groupId is required");
        GroupInformation info = groupManager.getGroupInformation(groupId);
        if (info == null) throw new NotFoundException("group not found");
        return info;
    }

    private Object handleSearch(ApiRequest req) {
        String keyword = req.getString("keyword");
        if (keyword == null || keyword.isBlank()) {
            throw new ValidationException("keyword is required");
        }
        int limit = req.getInt("limit", 20);
        List<GroupInformation> groups = groupManager.searchGroups(keyword.trim(), limit);
        return Map.of("groups", groups, "count", groups.size());
    }

    private Object handleMembers(ApiRequest req) {
        String groupId = req.getString("groupId");
        if (groupId == null) throw new ValidationException("groupId is required");
        List<GroupMemberInformation> members = groupManager.getMemberList(groupId);
        return Map.of("groupId", groupId, "members", members, "count", members.size());
    }

    private Object handleMuteAll(ApiRequest req) {
        String groupId = req.getString("groupId");
        String operatorId = req.currentUserId();
        boolean mute = req.getBoolean("mute", true);
        if (operatorId == null) throw new UnauthorizedException("not authenticated");
        if (groupId == null) throw new ValidationException("groupId is required");
        groupManager.muteGroupAll(groupId, operatorId, mute);
        return Map.of("status", "OK", "mute", mute);
    }
}
