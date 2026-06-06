package com.im.core.group;

import com.im.api.ApplyHandleResult;
import com.im.api.GroupApply;
import com.im.api.GroupJoinResult;
import com.im.api.GroupJoinVerification;
import com.im.api.GroupStatus;
import com.im.common.exception.ForbiddenException;
import com.im.common.exception.NotFoundException;
import com.im.common.exception.ValidationException;

import java.util.List;

public final class GroupApplyPolicy {

    public record GroupSnapshot(GroupStatus status, GroupJoinVerification joinVerification) {
    }

    public interface Gateway {
        GroupSnapshot getGroup(String groupId);
        boolean isMember(String groupId, String userId);
        List<GroupApply> getJoinRequests(String groupId, boolean onlyPending);
    }

    private final Gateway gateway;

    public GroupApplyPolicy(Gateway gateway) {
        this.gateway = gateway;
    }

    public GroupJoinResult validateJoin(String groupId, String userId) {
        if (groupId == null || groupId.isBlank()) {
            throw new ValidationException("groupId is required");
        }
        if (userId == null || userId.isBlank()) {
            throw new ValidationException("userId is required");
        }

        GroupSnapshot group = gateway.getGroup(groupId);
        if (group == null) {
            throw new NotFoundException("group not found");
        }
        if (group.status() != GroupStatus.NORMAL) {
            throw new ForbiddenException("group is not available");
        }
        if (gateway.isMember(groupId, userId)) {
            return GroupJoinResult.ALREADY_MEMBER;
        }
        if (hasPendingApply(groupId, userId)) {
            return GroupJoinResult.ALREADY_PENDING;
        }

        GroupJoinVerification verification = group.joinVerification();
        if (verification == GroupJoinVerification.DIRECT) {
            return GroupJoinResult.JOINED;
        }
        if (verification == GroupJoinVerification.NEED_APPROVAL) {
            return GroupJoinResult.APPLY_CREATED;
        }
        if (verification == GroupJoinVerification.INVITE_ONLY) {
            throw new ForbiddenException("group only allows invitation");
        }
        throw new ForbiddenException("group does not allow joining");
    }

    private boolean hasPendingApply(String groupId, String userId) {
        return gateway.getJoinRequests(groupId, true).stream()
                .anyMatch(apply -> userId.equals(apply.getUserId())
                        && apply.getHandleResult() == ApplyHandleResult.PENDING);
    }
}
