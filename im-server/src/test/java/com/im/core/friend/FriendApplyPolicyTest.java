package com.im.core.friend;

import com.im.api.ApplyHandleResult;
import com.im.api.FriendApply;
import com.im.common.exception.ForbiddenException;
import com.im.common.exception.ValidationException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FriendApplyPolicyTest {

    @Test
    void rejectsApplyingToSelf() {
        FakeFriendGateway gateway = new FakeFriendGateway();
        FriendApplyPolicy policy = new FriendApplyPolicy(gateway);

        assertThrows(ValidationException.class, () -> policy.validateApply("alice", "alice"));
    }

    @Test
    void rejectsApplyingWhenAlreadyFriends() {
        FakeFriendGateway gateway = new FakeFriendGateway();
        gateway.friends = true;
        FriendApplyPolicy policy = new FriendApplyPolicy(gateway);

        assertThrows(ForbiddenException.class, () -> policy.validateApply("alice", "bob"));
    }

    @Test
    void rejectsApplyingWhenEitherSideBlockedTheOther() {
        FakeFriendGateway gateway = new FakeFriendGateway();
        gateway.blocked = true;
        FriendApplyPolicy policy = new FriendApplyPolicy(gateway);

        assertThrows(ForbiddenException.class, () -> policy.validateApply("alice", "bob"));
    }

    @Test
    void duplicatePendingApplyIsIdempotent() {
        FakeFriendGateway gateway = new FakeFriendGateway();
        gateway.sameDirectionPending = true;
        FriendApplyPolicy policy = new FriendApplyPolicy(gateway);

        FriendApplyPolicy.Decision decision = policy.validateApply("alice", "bob");

        assertEquals(FriendApplyPolicy.Decision.ALREADY_PENDING, decision);
    }

    @Test
    void reversePendingApplyRequiresHandlingExistingApplyFirst() {
        FakeFriendGateway gateway = new FakeFriendGateway();
        gateway.reversePending = true;
        FriendApplyPolicy policy = new FriendApplyPolicy(gateway);

        assertThrows(ForbiddenException.class, () -> policy.validateApply("alice", "bob"));
    }

    private static final class FakeFriendGateway implements FriendApplyPolicy.Gateway {
        boolean friends;
        boolean blocked;
        boolean sameDirectionPending;
        boolean reversePending;

        @Override public boolean isFriend(String userIdA, String userIdB) { return friends; }
        @Override public boolean isBlocked(String fromUserId, String toUserId) { return blocked; }
        @Override public List<FriendApply> getSentFriendApplyList(String userId) {
            if (sameDirectionPending && userId.equals("alice")) return List.of(apply("alice", "bob"));
            if (reversePending && userId.equals("bob")) return List.of(apply("bob", "alice"));
            return List.of();
        }

        private static FriendApply apply(String from, String to) {
            FriendApply apply = new FriendApply();
            apply.setFromUserId(from);
            apply.setToUserId(to);
            apply.setHandleResult(ApplyHandleResult.PENDING);
            return apply;
        }
    }
}
