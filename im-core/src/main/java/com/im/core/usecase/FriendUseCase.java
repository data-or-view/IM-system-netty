package com.im.core.usecase;

import com.im.api.FriendInformation;
import com.im.api.IFriendManager;

import java.util.List;

public class FriendUseCase {

    private final IFriendManager friendManager;

    public FriendUseCase(IFriendManager friendManager) {
        this.friendManager = friendManager;
    }

    public void applyAddFriend(String fromUserId, String toUserId, String reqMsg) {
        friendManager.applyAddFriend(fromUserId, toUserId, reqMsg);
    }

    public void respondFriendApply(String userId, String fromUserId, String handleMsg, boolean agreed) {
        friendManager.respondFriendApply(userId, fromUserId, handleMsg, agreed);
    }

    public void deleteFriend(String ownerUserId, String friendUserId) {
        friendManager.deleteFriend(ownerUserId, friendUserId);
    }

    public List<FriendInformation> getFriendList(String userId) {
        return friendManager.getFriendList(userId);
    }
}
