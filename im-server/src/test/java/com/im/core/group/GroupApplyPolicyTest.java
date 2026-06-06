package com.im.core.group;

import com.im.api.ApplyHandleResult;
import com.im.api.GroupApply;
import com.im.api.GroupJoinResult;
import com.im.api.GroupJoinVerification;
import com.im.api.GroupStatus;
import com.im.common.exception.ForbiddenException;
import com.im.common.exception.NotFoundException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GroupApplyPolicyTest {

    @Test
    void rejectsMissingGroup() {
        FakeGateway gateway = new FakeGateway();
        gateway.groupExists = false;
        GroupApplyPolicy policy = new GroupApplyPolicy(gateway);

        assertThrows(NotFoundException.class, () -> policy.validateJoin("grp_1", "alice"));
    }

    @Test
    void rejectsDisbandedGroup() {
        FakeGateway gateway = new FakeGateway();
        gateway.status = GroupStatus.DISBANDED;
        GroupApplyPolicy policy = new GroupApplyPolicy(gateway);

        assertThrows(ForbiddenException.class, () -> policy.validateJoin("grp_1", "alice"));
    }

    @Test
    void alreadyMemberIsIdempotent() {
        FakeGateway gateway = new FakeGateway();
        gateway.member = true;
        GroupApplyPolicy policy = new GroupApplyPolicy(gateway);

        assertEquals(GroupJoinResult.ALREADY_MEMBER, policy.validateJoin("grp_1", "alice"));
    }

    @Test
    void directJoinCanJoinImmediately() {
        FakeGateway gateway = new FakeGateway();
        gateway.verification = GroupJoinVerification.DIRECT;
        GroupApplyPolicy policy = new GroupApplyPolicy(gateway);

        assertEquals(GroupJoinResult.JOINED, policy.validateJoin("grp_1", "alice"));
    }

    @Test
    void duplicatePendingApplyIsIdempotent() {
        FakeGateway gateway = new FakeGateway();
        gateway.pending = true;
        GroupApplyPolicy policy = new GroupApplyPolicy(gateway);

        assertEquals(GroupJoinResult.ALREADY_PENDING, policy.validateJoin("grp_1", "alice"));
    }

    @Test
    void needApprovalCreatesPendingApply() {
        FakeGateway gateway = new FakeGateway();
        gateway.verification = GroupJoinVerification.NEED_APPROVAL;
        GroupApplyPolicy policy = new GroupApplyPolicy(gateway);

        assertEquals(GroupJoinResult.APPLY_CREATED, policy.validateJoin("grp_1", "alice"));
    }

    @Test
    void inviteOnlyRejectsUserInitiatedJoin() {
        FakeGateway gateway = new FakeGateway();
        gateway.verification = GroupJoinVerification.INVITE_ONLY;
        GroupApplyPolicy policy = new GroupApplyPolicy(gateway);

        assertThrows(ForbiddenException.class, () -> policy.validateJoin("grp_1", "alice"));
    }

    private static final class FakeGateway implements GroupApplyPolicy.Gateway {
        boolean groupExists = true;
        boolean member;
        boolean pending;
        GroupStatus status = GroupStatus.NORMAL;
        GroupJoinVerification verification = GroupJoinVerification.NEED_APPROVAL;

        @Override public GroupApplyPolicy.GroupSnapshot getGroup(String groupId) {
            return groupExists ? new GroupApplyPolicy.GroupSnapshot(status, verification) : null;
        }
        @Override public boolean isMember(String groupId, String userId) { return member; }
        @Override public List<GroupApply> getJoinRequests(String groupId, boolean onlyPending) {
            if (!pending) return List.of();
            GroupApply apply = new GroupApply();
            apply.setGroupId(groupId);
            apply.setUserId("alice");
            apply.setHandleResult(ApplyHandleResult.PENDING);
            return List.of(apply);
        }
    }
}
