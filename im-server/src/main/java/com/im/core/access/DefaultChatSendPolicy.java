package com.im.core.access;

import com.im.api.IChatSendPolicy;
import com.im.api.IFriendManager;
import com.im.api.IGroupManager;
import com.im.api.IUserManager;
import com.im.common.exception.ValidationException;
import com.im.common.exception.ForbiddenException;
import com.im.common.exception.NotFoundException;
import com.im.common.validation.Preconditions;

/**
 * Default send authorization policy for single and group chat messages.
 */
public class DefaultChatSendPolicy implements IChatSendPolicy {

    private final IUserManager userManager;
    private final IFriendManager friendManager;
    private final IGroupManager groupManager;
    private final boolean requireFriendForSingleChat;

    public DefaultChatSendPolicy(IUserManager userManager,
                                 IFriendManager friendManager,
                                 IGroupManager groupManager,
                                 boolean requireFriendForSingleChat) {
        this.userManager = userManager;
        this.friendManager = friendManager;
        this.groupManager = groupManager;
        this.requireFriendForSingleChat = requireFriendForSingleChat;
    }

    @Override
    public void requireCanSendSingle(String fromUserId, String toUserId) {
        fromUserId = Preconditions.requireText(fromUserId, "fromUserId");
        toUserId = Preconditions.requireText(toUserId, "toUserId");
        if (fromUserId.equals(toUserId)) {
            throw new ValidationException("cannot send single chat to self");
        }
        if (userManager != null && userManager.getUserInformation(toUserId) == null) {
            throw new NotFoundException("target user not found");
        }
        if (friendManager != null && friendManager.isBlocked(fromUserId, toUserId)) {
            throw new ForbiddenException("blocked by target user");
        }
        if (requireFriendForSingleChat && friendManager != null && !friendManager.isFriend(fromUserId, toUserId)) {
            throw new ForbiddenException("single chat requires friend relation");
        }
    }

    @Override
    public void requireCanSendGroup(String fromUserId, String groupId) {
        fromUserId = Preconditions.requireText(fromUserId, "fromUserId");
        groupId = Preconditions.requireText(groupId, "groupId");
        if (groupManager != null && !groupManager.isMember(groupId, fromUserId)) {
            throw new ForbiddenException("not a group member");
        }
        if (groupManager != null && groupManager.isMemberMuted(groupId, fromUserId)) {
            throw new ForbiddenException("group member muted");
        }
    }

}
