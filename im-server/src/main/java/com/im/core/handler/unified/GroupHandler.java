package com.im.core.handler.unified;

import com.im.api.ApiRequest;
import com.im.api.GroupApply;
import com.im.api.GroupInformation;
import com.im.api.GroupJoinResult;
import com.im.api.GroupMemberInformation;
import com.im.api.GroupMemberRole;
import com.im.api.IConversationManager;
import com.im.api.IGroupManager;
import com.im.api.RequestPreconditions;
import com.im.api.RequestHandler;
import com.im.common.exception.ValidationException;
import com.im.common.exception.NotFoundException;
import com.im.common.validation.Preconditions;
import com.im.api.GroupApplyNotifier;
import com.im.api.GroupSystemMessagePublisher;
import com.im.core.system.SystemMessagePublishUseCase;
import com.im.core.group.GroupWorkflow;

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
    private final GroupWorkflow workflow;

    public GroupHandler(IGroupManager groupManager) {
        this(groupManager, GroupApplyNotifier.NOOP);
    }

    public GroupHandler(IGroupManager groupManager, GroupApplyNotifier groupApplyNotifier) {
        this(groupManager, groupApplyNotifier, GroupSystemMessagePublisher.NOOP);
    }

    public GroupHandler(IGroupManager groupManager, GroupApplyNotifier groupApplyNotifier,
                        GroupSystemMessagePublisher groupSystemMessagePublisher) {
        this(groupManager, groupApplyNotifier, groupSystemMessagePublisher, null);
    }

    public GroupHandler(IGroupManager groupManager, GroupApplyNotifier groupApplyNotifier,
                        GroupSystemMessagePublisher groupSystemMessagePublisher,
                        IConversationManager conversationManager) {
        this(groupManager, groupApplyNotifier, groupSystemMessagePublisher, conversationManager, null);
    }

    public GroupHandler(IGroupManager groupManager, GroupApplyNotifier groupApplyNotifier,
                        GroupSystemMessagePublisher groupSystemMessagePublisher,
                        IConversationManager conversationManager,
                        SystemMessagePublishUseCase systemMessagePublishUseCase) {
        this.groupManager = groupManager;
        this.workflow = new GroupWorkflow(
                groupManager,
                groupApplyNotifier,
                groupSystemMessagePublisher,
                conversationManager,
                systemMessagePublishUseCase);
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
            case "group.owner.transfer" -> handleOwnerTransfer(req);
            case "group.member.role.set" -> handleMemberRoleSet(req);
            case "group.member.info.update" -> handleMemberInfoUpdate(req);
            case "group.info" -> handleInfo(req);
            case "group.list" -> handleList(req);
            case "group.search" -> handleSearch(req);
            case "group.members" -> handleMembers(req);
            case "group.mute_all" -> handleMuteAll(req);
            case "group.apply.list" -> handleApplyList(req);
            case "group.apply.unhandled.count" -> handleApplyUnhandledCount(req);
            case "group.apply.approve" -> handleApplyApprove(req);
            default -> throw new NotFoundException("unsupported: " + req.operation());
        };
    }

    private Object handleCreate(ApiRequest req) {
        String groupName = req.getString("groupName");
        String ownerId = RequestPreconditions.requireUser(req);
        groupName = Preconditions.requireText(groupName, "groupName");
        String faceUrl = req.getString("faceUrl", "");
        int groupType = req.getInt("groupType", 0);
        int needVerification = req.getInt("needVerification", 0);
        List<String> members = (List<String>) req.param("members");
        if (members == null) members = List.of();

        GroupWorkflow.CreateResult result = workflow.createGroup(
                ownerId, groupName, faceUrl, groupType, needVerification, members);
        GroupInformation info = result.groupInformation();
        return info != null ? info : Map.of("groupId", result.groupId(), "status", "OK");
    }

    private Object handleJoin(ApiRequest req) {
        String groupId = req.getString("groupId");
        String userId = RequestPreconditions.requireUser(req);
        groupId = Preconditions.requireText(groupId, "groupId");
        String reqMsg = req.getString("reqMsg", "");
        GroupJoinResult result = workflow.joinGroup(groupId, userId, reqMsg);
        return Map.of("status", result.name(), "result", result.name());
    }

    private Object handleQuit(ApiRequest req) {
        String groupId = req.getString("groupId");
        String userId = RequestPreconditions.requireUser(req);
        groupId = Preconditions.requireText(groupId, "groupId");
        workflow.quitGroup(groupId, userId);
        return Map.of("status", "OK");
    }

    private Object handleKick(ApiRequest req) {
        String groupId = req.getString("groupId");
        String operatorId = RequestPreconditions.requireUser(req);
        String targetUserId = req.getString("targetUserId");
        if (groupId == null || targetUserId == null) {
            throw new ValidationException("groupId and targetUserId are required");
        }
        workflow.kickMember(groupId, operatorId, targetUserId);
        return Map.of("status", "OK");
    }

    private Object handleDisband(ApiRequest req) {
        String groupId = req.getString("groupId");
        String operatorId = RequestPreconditions.requireUser(req);
        groupId = Preconditions.requireText(groupId, "groupId");
        workflow.disbandGroup(groupId, operatorId);
        return Map.of("status", "OK");
    }

    private Object handleInfoUpdate(ApiRequest req) {
        String groupId = req.getString("groupId");
        groupId = Preconditions.requireText(groupId, "groupId");
        workflow.updateGroupInfo(groupId,
                req.getString("groupName"), req.getString("notification"),
                req.getString("introduction"), req.getString("faceUrl"),
                req.getInt("needVerification", -1),
                req.getInt("lookMemberInfo", -1),
                req.getInt("applyMemberFriend", -1),
                req.currentUserId());
        return Map.of("status", "OK");
    }

    private Object handleOwnerTransfer(ApiRequest req) {
        String groupId = Preconditions.requireText(req.getString("groupId"), "groupId");
        String operatorId = RequestPreconditions.requireUser(req);
        String targetUserId = Preconditions.requireText(req.getString("targetUserId"), "targetUserId");
        workflow.transferOwner(groupId, operatorId, targetUserId);
        return Map.of("status", "OK");
    }

    private Object handleMemberRoleSet(ApiRequest req) {
        String groupId = Preconditions.requireText(req.getString("groupId"), "groupId");
        String operatorId = RequestPreconditions.requireUser(req);
        String targetUserId = Preconditions.requireText(req.getString("targetUserId"), "targetUserId");
        GroupMemberRole role = parseMutableRole(req.param("roleLevel"));
        workflow.setMemberRole(groupId, operatorId, targetUserId, role);
        return Map.of("status", "OK", "roleLevel", role.name());
    }

    private Object handleMemberInfoUpdate(ApiRequest req) {
        String groupId = Preconditions.requireText(req.getString("groupId"), "groupId");
        String userId = RequestPreconditions.requireUser(req);
        String nickname = Preconditions.requireText(req.getString("nickname"), "nickname");
        workflow.updateMemberInfo(groupId, userId, nickname);
        return Map.of("status", "OK");
    }

    private Object handleInfo(ApiRequest req) {
        String groupId = req.getString("groupId");
        groupId = Preconditions.requireText(groupId, "groupId");
        GroupInformation info = groupManager.getGroupInformation(groupId);
        if (info == null) throw new NotFoundException("group not found");
        return info;
    }

    private Object handleList(ApiRequest req) {
        String userId = RequestPreconditions.requireUser(req);
        List<GroupInformation> groups = groupManager.getJoinedGroupInformationList(userId);
        return Map.of("groups", groups, "count", groups.size());
    }

    private Object handleSearch(ApiRequest req) {
        String keyword = req.getString("keyword");
        keyword = Preconditions.requireText(keyword, "keyword");
        int limit = req.getInt("limit", 20);
        List<GroupInformation> groups = groupManager.searchGroups(keyword.trim(), limit);
        return Map.of("groups", groups, "count", groups.size());
    }

    private Object handleMembers(ApiRequest req) {
        String groupId = req.getString("groupId");
        groupId = Preconditions.requireText(groupId, "groupId");
        List<GroupMemberInformation> members = groupManager.getMemberList(groupId);
        return Map.of("groupId", groupId, "members", members, "count", members.size());
    }

    private Object handleMuteAll(ApiRequest req) {
        String groupId = req.getString("groupId");
        String operatorId = RequestPreconditions.requireUser(req);
        boolean mute = req.getBoolean("mute", true);
        groupId = Preconditions.requireText(groupId, "groupId");
        workflow.muteAll(groupId, operatorId, mute);
        return Map.of("status", "OK", "mute", mute);
    }

    private Object handleApplyList(ApiRequest req) {
        String operatorId = RequestPreconditions.requireUser(req);
        boolean onlyPending = req.getBoolean("onlyPending", true);
        List<GroupApply> applies = groupManager.getManageableJoinRequests(operatorId, onlyPending);
        return Map.of("operatorId", operatorId, "applies", applies, "count", applies.size());
    }

    private Object handleApplyUnhandledCount(ApiRequest req) {
        String operatorId = RequestPreconditions.requireUser(req);
        int count = groupManager.getManageableJoinRequests(operatorId, true).size();
        return Map.of("count", count);
    }

    private Object handleApplyApprove(ApiRequest req) {
        String operatorId = RequestPreconditions.requireUser(req);
        String groupId = req.getString("groupId");
        String userId = req.getString("userId");
        boolean agreed = req.getBoolean("agreed", true);
        String handleMsg = req.getString("handleMsg", "");
        if (groupId == null || userId == null) {
            throw new ValidationException("groupId and userId are required");
        }
        workflow.approveApply(groupId, userId, operatorId, handleMsg, agreed);
        return Map.of("status", "OK");
    }

    private GroupMemberRole parseMutableRole(Object value) {
        if (value instanceof Number number) {
            GroupMemberRole role = GroupMemberRole.fromCode(number.intValue());
            return requireMutableRole(role);
        }
        if (value instanceof String text) {
            String trimmed = Preconditions.requireText(text, "roleLevel");
            try {
                return requireMutableRole(GroupMemberRole.valueOf(trimmed.toUpperCase()));
            } catch (IllegalArgumentException ignored) {
                try {
                    return requireMutableRole(GroupMemberRole.fromCode(Integer.parseInt(trimmed)));
                } catch (NumberFormatException ex) {
                    throw new ValidationException("roleLevel must be MEMBER or ADMIN");
                }
            }
        }
        throw new ValidationException("roleLevel is required");
    }

    private GroupMemberRole requireMutableRole(GroupMemberRole role) {
        if (role != GroupMemberRole.MEMBER && role != GroupMemberRole.ADMIN) {
            throw new ValidationException("roleLevel must be MEMBER or ADMIN");
        }
        return role;
    }
}
