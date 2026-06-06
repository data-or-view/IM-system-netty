package com.im.core.handler.unified;

import com.im.api.ApiRequest;
import com.im.api.FriendInformation;
import com.im.api.IFriendManager;
import com.im.api.RequestHandler;
import com.im.common.exception.UnauthorizedException;
import com.im.common.exception.ValidationException;
import com.im.common.exception.ForbiddenException;
import com.im.common.exception.NotFoundException;
import com.im.core.friend.FriendApplyNotifier;

import java.util.List;
import java.util.Map;

/**
 * 好友域 handler：申请、审批、删除、列表、黑名单。
 *
 * <p>合并 WS {@code FriendHandler} + HTTP {@code FriendRestHandler}。</p>
 */
public class FriendHandler implements RequestHandler {

    private final IFriendManager friendManager;
    private final FriendApplyNotifier friendApplyNotifier;

    public FriendHandler(IFriendManager friendManager) {
        this(friendManager, FriendApplyNotifier.NOOP);
    }

    public FriendHandler(IFriendManager friendManager, FriendApplyNotifier friendApplyNotifier) {
        this.friendManager = friendManager;
        this.friendApplyNotifier = friendApplyNotifier != null ? friendApplyNotifier : FriendApplyNotifier.NOOP;
    }

    @Override
    public Object handle(ApiRequest req) {
        return switch (req.operation()) {
            case "friend.apply" -> handleApply(req);
            case "friend.approve" -> handleApprove(req);
            case "friend.remove" -> handleRemove(req);
            case "friend.list" -> handleList(req);
            case "friend.black" -> handleAddBlack(req);
            case "friend.unblack" -> handleRemoveBlack(req);
            case "friend.blacklist" -> handleBlackList(req);
            case "friend.get_apply_list" -> handleReceivedApplyList(req);
            case "friend.get_sent_apply_list" -> handleSentApplyList(req);
            case "friend.get_apply_detail" -> handleApplyDetail(req);
            case "friend.get_unhandled_apply_count" -> handleUnhandledApplyCount(req);
            default -> throw new NotFoundException("unsupported: " + req.operation());
        };
    }

    private Map<String, String> handleApply(ApiRequest req) {
        String fromUserId = req.currentUserId();
        String toUserId = req.getString("toUserId");
        String reqMsg = req.getString("reqMsg", "");
        if (fromUserId == null) throw new UnauthorizedException("not authenticated");
        if (toUserId == null) throw new ValidationException("toUserId is required");
        friendManager.applyAddFriend(fromUserId, toUserId, reqMsg);
        var apply = friendManager.getFriendApplyDetail(fromUserId, toUserId);
        if (apply != null) {
            friendApplyNotifier.notifyApplyCreated(toUserId, apply);
        }
        return Map.of("status", "OK");
    }

    private Map<String, String> handleApprove(ApiRequest req) {
        String userId = req.currentUserId();
        String fromUserId = req.getString("fromUserId");
        boolean agreed = req.getBoolean("agreed", true);
        String handleMsg = req.getString("handleMsg", "");
        if (userId == null) throw new UnauthorizedException("not authenticated");
        if (fromUserId == null) throw new ValidationException("fromUserId is required");
        friendManager.respondFriendApply(userId, fromUserId, handleMsg, agreed);
        var apply = friendManager.getFriendApplyDetail(fromUserId, userId);
        if (apply != null) {
            friendApplyNotifier.notifyApplyHandled(fromUserId, apply);
        }
        return Map.of("status", "OK");
    }

    private Map<String, String> handleRemove(ApiRequest req) {
        String userId = req.currentUserId();
        String friendUserId = req.getString("friendUserId");
        if (userId == null) throw new UnauthorizedException("not authenticated");
        if (friendUserId == null) throw new ValidationException("friendUserId is required");
        friendManager.deleteFriend(userId, friendUserId);
        return Map.of("status", "OK");
    }

    private Object handleList(ApiRequest req) {
        String userId = req.currentUserId();
        if (userId == null) throw new UnauthorizedException("not authenticated");
        List<FriendInformation> friends = friendManager.getFriendList(userId);
        return Map.of("userId", userId, "friends", friends, "count", friends.size());
    }

    private Map<String, String> handleAddBlack(ApiRequest req) {
        String userId = req.currentUserId();
        String blockedUserId = req.getString("blockedUserId");
        if (userId == null) throw new UnauthorizedException("not authenticated");
        if (blockedUserId == null) throw new ValidationException("blockedUserId is required");
        friendManager.addBlack(userId, blockedUserId);
        return Map.of("status", "OK");
    }

    private Map<String, String> handleRemoveBlack(ApiRequest req) {
        String userId = req.currentUserId();
        String blockedUserId = req.getString("blockedUserId");
        if (userId == null) throw new UnauthorizedException("not authenticated");
        if (blockedUserId == null) throw new ValidationException("blockedUserId is required");
        friendManager.removeBlack(userId, blockedUserId);
        return Map.of("status", "OK");
    }

    private Object handleBlackList(ApiRequest req) {
        String userId = req.currentUserId();
        if (userId == null) throw new UnauthorizedException("not authenticated");
        return Map.of("userId", userId, "blacklist", friendManager.getBlackList(userId));
    }

    private Object handleSentApplyList(ApiRequest req) {
        String userId = req.currentUserId();
        if (userId == null) throw new UnauthorizedException("not authenticated");
        var applies = friendManager.getSentFriendApplyList(userId);
        return Map.of("applies", applies, "count", applies.size());
    }

    private Object handleReceivedApplyList(ApiRequest req) {
        String userId = req.currentUserId();
        if (userId == null) throw new UnauthorizedException("not authenticated");
        boolean onlyPending = req.getBoolean("onlyPending", true);
        var applies = friendManager.getFriendApplyList(userId, onlyPending);
        return Map.of("userId", userId, "applies", applies, "count", applies.size());
    }

    private Object handleApplyDetail(ApiRequest req) {
        String userId = req.currentUserId();
        String fromUserId = req.getString("fromUserId");
        String toUserId = req.getString("toUserId");
        if (userId == null) throw new UnauthorizedException("not authenticated");
        if (fromUserId == null || toUserId == null) {
            throw new ValidationException("fromUserId and toUserId are required");
        }
        // 只有申请双方可以查看详情
        if (!userId.equals(fromUserId) && !userId.equals(toUserId)) {
            throw new ForbiddenException("not authorized to view this apply");
        }
        var apply = friendManager.getFriendApplyDetail(fromUserId, toUserId);
        if (apply == null) throw new NotFoundException("friend apply not found");
        return apply;
    }

    private Object handleUnhandledApplyCount(ApiRequest req) {
        String userId = req.currentUserId();
        if (userId == null) throw new UnauthorizedException("not authenticated");
        int count = friendManager.getUnhandledApplyCount(userId);
        return Map.of("count", count);
    }
}
