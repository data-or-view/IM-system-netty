package com.im.core.friend;

import com.im.api.ApplyHandleResult;
import com.im.api.FriendApply;
import com.im.common.exception.ForbiddenException;
import com.im.common.exception.ValidationException;

import java.util.List;

/**
 * Keeps friend-apply rules in one place so HTTP handlers and DB code do not
 * drift into different interpretations of the relationship state machine.
 */
public final class FriendApplyPolicy {

    public enum Decision {
        CREATE_PENDING,
        ALREADY_PENDING
    }

    public interface Gateway {
        boolean isFriend(String userIdA, String userIdB);
        boolean isBlocked(String fromUserId, String toUserId);
        List<FriendApply> getSentFriendApplyList(String userId);
    }

    private final Gateway gateway;

    public FriendApplyPolicy(Gateway gateway) {
        this.gateway = gateway;
    }

    public Decision validateApply(String fromUserId, String toUserId) {
        if (fromUserId == null || toUserId == null || toUserId.isBlank()) {
            throw new ValidationException("toUserId is required");
        }
        if (fromUserId.equals(toUserId)) {
            throw new ValidationException("cannot apply friend to self");
        }
        if (gateway.isFriend(fromUserId, toUserId)) {
            throw new ForbiddenException("already friends");
        }
        if (gateway.isBlocked(fromUserId, toUserId) || gateway.isBlocked(toUserId, fromUserId)) {
            throw new ForbiddenException("friend apply blocked by blacklist");
        }
        if (hasPendingApply(fromUserId, toUserId)) {
            return Decision.ALREADY_PENDING;
        }
        if (hasPendingApply(toUserId, fromUserId)) {
            throw new ForbiddenException("reverse friend apply already pending");
        }
        return Decision.CREATE_PENDING;
    }

    private boolean hasPendingApply(String fromUserId, String toUserId) {
        return gateway.getSentFriendApplyList(fromUserId).stream()
                .anyMatch(apply -> toUserId.equals(apply.getToUserId())
                        && apply.getHandleResult() == ApplyHandleResult.PENDING);
    }
}
