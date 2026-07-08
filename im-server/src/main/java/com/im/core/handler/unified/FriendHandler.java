package com.im.core.handler.unified;

import com.im.api.ApiRequest;
import com.im.api.ConversationIds;
import com.im.api.FriendInformation;
import com.im.api.IConversationManager;
import com.im.api.IFriendManager;
import com.im.api.Operation;
import com.im.api.RequestPreconditions;
import com.im.api.RequestHandler;
import com.im.common.exception.ValidationException;
import com.im.common.exception.ForbiddenException;
import com.im.common.exception.NotFoundException;
import com.im.common.validation.Preconditions;
import com.im.api.FriendApplyNotifier;
import com.im.core.observability.LogEvents;
import com.im.core.observability.LogFields;
import com.im.core.observability.StructuredLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * 好友域 handler：申请、审批、删除、列表、黑名单。
 *
 * <p>合并 WS {@code FriendHandler} + HTTP {@code FriendRestHandler}。</p>
 */
public class FriendHandler implements RequestHandler {

    private static final Logger log = LoggerFactory.getLogger(FriendHandler.class);

    private final IFriendManager friendManager;
    private final FriendApplyNotifier friendApplyNotifier;
    private final IConversationManager conversationManager;

    public FriendHandler(IFriendManager friendManager) {
        this(friendManager, FriendApplyNotifier.NOOP);
    }

    public FriendHandler(IFriendManager friendManager, FriendApplyNotifier friendApplyNotifier) {
        this(friendManager, friendApplyNotifier, null);
    }

    public FriendHandler(IFriendManager friendManager, FriendApplyNotifier friendApplyNotifier,
                         IConversationManager conversationManager) {
        this.friendManager = friendManager;
        this.friendApplyNotifier = friendApplyNotifier != null ? friendApplyNotifier : FriendApplyNotifier.NOOP;
        this.conversationManager = conversationManager;
    }

    @Override
    public Object handle(ApiRequest req) {
        Operation operation = Operation.fromOpName(req.operation());
        if (operation == null) throw new NotFoundException("unsupported: " + req.operation());
        return switch (operation) {
            case FRIEND_APPLY -> handleApply(req);
            case FRIEND_APPROVE -> handleApprove(req);
            case FRIEND_REMOVE -> handleRemove(req);
            case FRIEND_LIST -> handleList(req);
            case FRIEND_BLACK -> handleAddBlack(req);
            case FRIEND_UNBLACK -> handleRemoveBlack(req);
            case FRIEND_BLACKLIST -> handleBlackList(req);
            case FRIEND_APPLY_RECEIVED -> handleReceivedApplyList(req);
            case FRIEND_APPLY_SENT -> handleSentApplyList(req);
            case FRIEND_APPLY_DETAIL -> handleApplyDetail(req);
            case FRIEND_APPLY_UNHANDLED_COUNT -> handleUnhandledApplyCount(req);
            default -> throw new NotFoundException("unsupported: " + req.operation());
        };
    }

    private Map<String, String> handleApply(ApiRequest req) {
        String fromUserId = RequestPreconditions.requireUser(req);
        String toUserId = req.getString("toUserId");
        String reqMsg = req.getString("reqMsg", "");
        toUserId = Preconditions.requireText(toUserId, "toUserId");
        friendManager.applyAddFriend(fromUserId, toUserId, reqMsg);
        var apply = friendManager.getFriendApplyDetail(fromUserId, toUserId);
        if (apply != null) {
            // 通知使用持久化后的申请记录，保证多端收到的 applyId、状态和时间与列表接口一致。
            friendApplyNotifier.notifyApplyCreated(toUserId, apply);
        }
        log.info(StructuredLog.event(LogEvents.FRIEND_APPLY_CREATED,
                LogFields.USER_ID, fromUserId,
                LogFields.TARGET_USER_ID, toUserId));
        return Map.of("status", "OK");
    }

    private Map<String, String> handleApprove(ApiRequest req) {
        String userId = RequestPreconditions.requireUser(req);
        String fromUserId = req.getString("fromUserId");
        boolean agreed = req.getBoolean("agreed", true);
        String handleMsg = req.getString("handleMsg", "");
        fromUserId = Preconditions.requireText(fromUserId, "fromUserId");
        friendManager.respondFriendApply(userId, fromUserId, handleMsg, agreed);
        var apply = friendManager.getFriendApplyDetail(fromUserId, userId);
        if (apply != null) {
            // 审批结果先落库再推送，避免对方收到通知后立刻刷新详情却读到旧状态。
            friendApplyNotifier.notifyApplyHandled(fromUserId, apply);
        }
        log.info(StructuredLog.event(LogEvents.FRIEND_APPLY_HANDLED,
                LogFields.USER_ID, userId,
                LogFields.TARGET_USER_ID, fromUserId,
                LogFields.STATUS, agreed ? "agreed" : "rejected"));
        return Map.of("status", "OK");
    }

    private Map<String, String> handleRemove(ApiRequest req) {
        String userId = RequestPreconditions.requireUser(req);
        String friendUserId = req.getString("friendUserId");
        friendUserId = Preconditions.requireText(friendUserId, "friendUserId");
        boolean removed = friendManager.deleteFriend(userId, friendUserId);
        if (removed && conversationManager != null) {
            conversationManager.deleteConversation(userId, ConversationIds.single(userId, friendUserId));
        }
        log.info(StructuredLog.event(LogEvents.FRIEND_REMOVED,
                LogFields.USER_ID, userId,
                LogFields.TARGET_USER_ID, friendUserId,
                LogFields.SUCCESS, removed));
        return Map.of("status", "OK");
    }

    private Object handleList(ApiRequest req) {
        String userId = RequestPreconditions.requireUser(req);
        List<FriendInformation> friends = friendManager.getFriendList(userId);
        return Map.of("userId", userId, "friends", friends, "count", friends.size());
    }

    private Map<String, String> handleAddBlack(ApiRequest req) {
        String userId = RequestPreconditions.requireUser(req);
        String blockedUserId = req.getString("blockedUserId");
        blockedUserId = Preconditions.requireText(blockedUserId, "blockedUserId");
        friendManager.addBlack(userId, blockedUserId);
        return Map.of("status", "OK");
    }

    private Map<String, String> handleRemoveBlack(ApiRequest req) {
        String userId = RequestPreconditions.requireUser(req);
        String blockedUserId = req.getString("blockedUserId");
        blockedUserId = Preconditions.requireText(blockedUserId, "blockedUserId");
        friendManager.removeBlack(userId, blockedUserId);
        return Map.of("status", "OK");
    }

    private Object handleBlackList(ApiRequest req) {
        String userId = RequestPreconditions.requireUser(req);
        return Map.of("userId", userId, "blacklist", friendManager.getBlackList(userId));
    }

    private Object handleSentApplyList(ApiRequest req) {
        String userId = RequestPreconditions.requireUser(req);
        var applies = friendManager.getSentFriendApplyList(userId);
        return Map.of("applies", applies, "count", applies.size());
    }

    private Object handleReceivedApplyList(ApiRequest req) {
        String userId = RequestPreconditions.requireUser(req);
        boolean onlyPending = req.getBoolean("onlyPending", true);
        var applies = friendManager.getFriendApplyList(userId, onlyPending);
        return Map.of("userId", userId, "applies", applies, "count", applies.size());
    }

    private Object handleApplyDetail(ApiRequest req) {
        String userId = RequestPreconditions.requireUser(req);
        String fromUserId = req.getString("fromUserId");
        String toUserId = req.getString("toUserId");
        if (fromUserId == null || toUserId == null) {
            throw new ValidationException("fromUserId and toUserId are required");
        }
        // 申请详情包含双方处理状态和附言，只有申请双方能查看，不能仅凭登录态开放查询。
        if (!userId.equals(fromUserId) && !userId.equals(toUserId)) {
            throw new ForbiddenException("not authorized to view this apply");
        }
        var apply = friendManager.getFriendApplyDetail(fromUserId, toUserId);
        if (apply == null) throw new NotFoundException("friend apply not found");
        return apply;
    }

    private Object handleUnhandledApplyCount(ApiRequest req) {
        String userId = RequestPreconditions.requireUser(req);
        int count = friendManager.getUnhandledApplyCount(userId);
        return Map.of("count", count);
    }
}
