package com.im.core.handler.unified;

import com.im.api.ApiRequest;
import com.im.api.GroupApplyHandleResult;
import com.im.api.GroupApply;
import com.im.api.GroupDisbandResult;
import com.im.api.GroupInformation;
import com.im.api.GroupJoinResult;
import com.im.api.GroupMemberInformation;
import com.im.api.ConversationIds;
import com.im.api.IConversationManager;
import com.im.api.IGroupManager;
import com.im.api.RequestPreconditions;
import com.im.api.RequestHandler;
import com.im.common.exception.ForbiddenException;
import com.im.common.exception.ValidationException;
import com.im.common.exception.NotFoundException;
import com.im.common.id.IdGenerator;
import com.im.common.validation.Preconditions;
import com.im.api.GroupApplyNotifier;
import com.im.api.GroupSystemMessagePublisher;
import com.im.core.system.SystemMessagePublishUseCase;
import com.im.api.SystemMessage;

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
    private final GroupApplyNotifier groupApplyNotifier;
    private final GroupSystemMessagePublisher groupSystemMessagePublisher;
    private final IConversationManager conversationManager;
    private final SystemMessagePublishUseCase systemMessagePublishUseCase;

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
        this.groupApplyNotifier = groupApplyNotifier != null ? groupApplyNotifier : GroupApplyNotifier.NOOP;
        this.groupSystemMessagePublisher = groupSystemMessagePublisher != null
                ? groupSystemMessagePublisher : GroupSystemMessagePublisher.NOOP;
        this.conversationManager = conversationManager;
        this.systemMessagePublishUseCase = systemMessagePublishUseCase;
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
        String groupId = IdGenerator.groupId();
        String groupName = req.getString("groupName");
        String ownerId = RequestPreconditions.requireUser(req);
        groupName = Preconditions.requireText(groupName, "groupName");
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
        String userId = RequestPreconditions.requireUser(req);
        groupId = Preconditions.requireText(groupId, "groupId");
        String reqMsg = req.getString("reqMsg", "");
        GroupJoinResult result = groupManager.joinGroup(groupId, userId, reqMsg);
        if (result == GroupJoinResult.JOINED) {
            // 直接入群也要写系统消息，离线成员靠普通消息同步感知成员变更。
            groupSystemMessagePublisher.memberJoined(groupId, userId, userId);
        }
        if (result == GroupJoinResult.APPLY_CREATED) {
            GroupApply apply = findGroupApply(groupId, userId, true);
            if (apply != null) {
                // 通知管理员时使用已入库申请，避免多端审批页拿不到同一条申请的持久化状态。
                groupApplyNotifier.notifyApplyCreated(groupManager.getManagerIds(groupId), apply);
            }
        }
        return Map.of("status", "OK");
    }

    private Object handleQuit(ApiRequest req) {
        String groupId = req.getString("groupId");
        String userId = RequestPreconditions.requireUser(req);
        groupId = Preconditions.requireText(groupId, "groupId");
        boolean removed = groupManager.quitGroup(groupId, userId);
        if (removed) {
            // 退群人的会话要立即从自己的列表消失；剩余成员通过群消息流感知成员变更。
            if (conversationManager != null) {
                conversationManager.deleteConversation(userId, ConversationIds.group(groupId));
            }
            groupSystemMessagePublisher.memberLeft(groupId, userId, userId);
        }
        return Map.of("status", "OK");
    }

    private Object handleKick(ApiRequest req) {
        String groupId = req.getString("groupId");
        String operatorId = RequestPreconditions.requireUser(req);
        String targetUserId = req.getString("targetUserId");
        if (groupId == null || targetUserId == null) {
            throw new ValidationException("groupId and targetUserId are required");
        }
        groupManager.kickMember(groupId, operatorId, targetUserId);
        return Map.of("status", "OK");
    }

    private Object handleDisband(ApiRequest req) {
        String groupId = req.getString("groupId");
        String operatorId = RequestPreconditions.requireUser(req);
        groupId = Preconditions.requireText(groupId, "groupId");
        GroupDisbandResult result = groupManager.disbandGroup(groupId, operatorId);
        for (String memberId : result.getAffectedMemberIds()) {
            if (conversationManager != null) {
                conversationManager.deleteConversation(memberId, ConversationIds.group(groupId));
            }
        }
        publishGroupDisbandedMessage(result);
        return Map.of("status", "OK");
    }

    private void publishGroupDisbandedMessage(GroupDisbandResult result) {
        if (systemMessagePublishUseCase == null || result.getAffectedMemberIds().isEmpty()) return;
        SystemMessage message = new SystemMessage();
        message.setChannelId("group");
        message.setTitle("群聊已解散");
        String groupName = result.getGroupName() != null && !result.getGroupName().isBlank()
                ? result.getGroupName() : result.getGroupId();
        message.setSummary(groupName + "已解散");
        message.setContent(groupName + "已被群主解散");
        message.setContentType("group_disbanded");
        message.setSenderId(result.getOperatorId());
        systemMessagePublishUseCase.publishToUsers(message, result.getAffectedMemberIds());
    }

    private Object handleInfoUpdate(ApiRequest req) {
        String groupId = req.getString("groupId");
        groupId = Preconditions.requireText(groupId, "groupId");
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
        groupManager.muteGroupAll(groupId, operatorId, mute);
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
        // 审批入口必须在响应前再次校验管理员身份，不能只依赖前端展示的“管理员可见”入口。
        requireGroupAdmin(groupId, operatorId);
        GroupApplyHandleResult result = groupManager.respondJoinRequest(groupId, userId, operatorId, handleMsg, agreed);
        if (result == GroupApplyHandleResult.HANDLED) {
            GroupApply apply = findGroupApply(groupId, userId, false);
            if (apply != null) {
                // 被处理人在线时即时推送；不在线时仍可通过申请列表读取最终状态。
                groupApplyNotifier.notifyApplyHandled(userId, apply);
            }
            if (agreed) {
                // 同意入群后也走群消息流，保证群成员通过同一条历史链路看到成员变动。
                groupSystemMessagePublisher.memberJoined(groupId, userId, operatorId);
            }
        }
        return Map.of("status", "OK");
    }

    private GroupApply findGroupApply(String groupId, String userId, boolean onlyPending) {
        return groupManager.getJoinRequests(groupId, onlyPending).stream()
                .filter(apply -> userId.equals(apply.getUserId()))
                .findFirst()
                .orElse(null);
    }

    private void requireGroupAdmin(String groupId, String operatorId) {
        String role = groupManager.getRole(groupId, operatorId);
        if (!"owner".equals(role) && !"admin".equals(role)) {
            throw new ForbiddenException("only group owner or admin can operate group apply");
        }
    }
}
