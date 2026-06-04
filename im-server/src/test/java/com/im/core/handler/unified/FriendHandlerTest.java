package com.im.core.handler.unified;

import com.im.api.ApiRequest;
import com.im.api.FriendApply;
import com.im.api.FriendInformation;
import com.im.api.IFriendManager;
import com.im.api.Operation;
import com.im.common.exception.UnauthorizedException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;

class FriendHandlerTest {

    @Test
    void applyDetailWithoutAuthenticatedUserThrowsUnauthorized() {
        FriendHandler handler = new FriendHandler(new NoopFriendManager());
        ApiRequest request = new ApiRequest(Operation.FRIEND_APPLY_DETAIL,
                Map.of("fromUserId", "alice", "toUserId", "bob"), Map.of(), null, null);

        assertThrows(UnauthorizedException.class, () -> handler.handle(request));
    }

    private static class NoopFriendManager implements IFriendManager {
        @Override public void applyAddFriend(String fromUserId, String toUserId, String reqMsg) {}
        @Override public void respondFriendApply(String userId, String fromUserId, String handleMsg, boolean agreed) {}
        @Override public List<FriendApply> getFriendApplyList(String userId, boolean onlyPending) { return List.of(); }
        @Override public void deleteFriend(String ownerUserId, String friendUserId) {}
        @Override public List<FriendInformation> getFriendList(String userId) { return List.of(); }
        @Override public boolean isFriend(String userIdA, String userIdB) { return false; }
        @Override public void setFriendRemark(String ownerUserId, String friendUserId, String remark) {}
        @Override public void setFriendPinned(String ownerUserId, String friendUserId, boolean pinned) {}
        @Override public void addBlack(String ownerUserId, String blockedUserId) {}
        @Override public void removeBlack(String ownerUserId, String blockedUserId) {}
        @Override public List<String> getBlackList(String userId) { return List.of(); }
        @Override public boolean isBlocked(String fromUserId, String toUserId) { return false; }
    }
}
