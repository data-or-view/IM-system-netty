package com.im.core.friend;

import com.im.api.FriendApply;

public interface FriendApplyNotifier {

    void notifyApplyCreated(String toUserId, FriendApply apply);

    void notifyApplyHandled(String fromUserId, FriendApply apply);

    FriendApplyNotifier NOOP = new FriendApplyNotifier() {
        @Override public void notifyApplyCreated(String toUserId, FriendApply apply) {}
        @Override public void notifyApplyHandled(String fromUserId, FriendApply apply) {}
    };
}
