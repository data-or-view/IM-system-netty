package com.im.core.handler.unified;

import com.im.api.ApiRequest;
import com.im.api.GroupInformation;
import com.im.api.GroupMemberInformation;
import com.im.api.IGroupManager;
import com.im.api.RequestHandler;
import com.im.common.enums.ImErrorCode;
import com.im.common.exception.ImException;

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
            default -> throw new ImException(ImErrorCode.NOT_FOUND, "unsupported: " + req.operation());
        };
    }

    private Object handleCreate(ApiRequest req) {
        String groupId = req.getString("groupId");
        String groupName = req.getString("groupName");
        String ownerId = req.getString("ownerId");
        if (groupId == null || groupName == null || ownerId == null) {
            throw new ImException(ImErrorCode.BAD_REQUEST, "groupId, groupName, and ownerId are required");
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
        String userId = req.getString("userId");
        if (groupId == null || userId == null) {
            throw new ImException(ImErrorCode.BAD_REQUEST, "groupId and userId are required");
        }
        String reqMsg = req.getString("reqMsg", "");
        groupManager.joinGroup(groupId, userId, reqMsg);
        return Map.of("status", "OK");
    }

    private Object handleQuit(ApiRequest req) {
        String groupId = req.getString("groupId");
        String userId = req.getString("userId");
        if (groupId == null || userId == null) {
            throw new ImException(ImErrorCode.BAD_REQUEST, "groupId and userId are required");
        }
        groupManager.quitGroup(groupId, userId);
        return Map.of("status", "OK");
    }

    private Object handleKick(ApiRequest req) {
        String groupId = req.getString("groupId");
        String operatorId = req.getString("operatorId");
        String targetUserId = req.getString("targetUserId");
        if (groupId == null || operatorId == null || targetUserId == null) {
            throw new ImException(ImErrorCode.BAD_REQUEST, "groupId, operatorId, targetUserId are required");
        }
        groupManager.kickMember(groupId, operatorId, targetUserId);
        return Map.of("status", "OK");
    }

    private Object handleDisband(ApiRequest req) {
        String groupId = req.getString("groupId");
        String operatorId = req.getString("operatorId");
        if (groupId == null || operatorId == null) {
            throw new ImException(ImErrorCode.BAD_REQUEST, "groupId and operatorId are required");
        }
        groupManager.disbandGroup(groupId, operatorId);
        return Map.of("status", "OK");
    }

    private Object handleInfoUpdate(ApiRequest req) {
        String groupId = req.getString("groupId");
        if (groupId == null) throw new ImException(ImErrorCode.BAD_REQUEST, "groupId is required");
        groupManager.setGroupInformation(groupId,
                req.getString("groupName"), req.getString("notification"),
                req.getString("introduction"), req.getString("faceUrl"),
                req.getInt("needVerification", -1),
                req.getInt("lookMemberInfo", -1),
                req.getInt("applyMemberFriend", -1),
                req.getString("operatorId"));
        return Map.of("status", "OK");
    }

    private Object handleInfo(ApiRequest req) {
        String groupId = req.getString("groupId");
        if (groupId == null) throw new ImException(ImErrorCode.BAD_REQUEST, "groupId is required");
        GroupInformation info = groupManager.getGroupInformation(groupId);
        if (info == null) throw new ImException(ImErrorCode.NOT_FOUND, "group not found");
        return info;
    }

    private Object handleSearch(ApiRequest req) {
        String keyword = req.getString("keyword");
        if (keyword == null || keyword.isBlank()) {
            throw new ImException(ImErrorCode.BAD_REQUEST, "keyword is required");
        }
        int limit = req.getInt("limit", 20);
        List<GroupInformation> groups = groupManager.searchGroups(keyword.trim(), limit);
        return Map.of("groups", groups, "count", groups.size());
    }

    private Object handleMembers(ApiRequest req) {
        String groupId = req.getString("groupId");
        if (groupId == null) throw new ImException(ImErrorCode.BAD_REQUEST, "groupId is required");
        List<GroupMemberInformation> members = groupManager.getMemberList(groupId);
        return Map.of("groupId", groupId, "members", members, "count", members.size());
    }
}
