package com.im.bootstrap.http;

import com.im.api.*;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

import static com.im.bootstrap.http.HttpParamUtils.*;

/**
 * 好友域 REST 控制器。
 *
 * <p>处理 /api/friend/* 路由：申请、审批、删除、列表、黑名单。</p>
 */
public class FriendRestHandler implements RestController {

    private static final Logger log = LoggerFactory.getLogger(FriendRestHandler.class);

    private final IFriendManager friendManager;

    public FriendRestHandler(IFriendManager friendManager) {
        this.friendManager = friendManager;
    }

    @Override
    public void register(HttpRestHandler router) {
        router.post("/api/friend/apply", this::handleApply);
        router.post("/api/friend/approve", this::handleApprove);
        router.post("/api/friend/remove", this::handleRemove);
        router.get("/api/friend/list", this::handleList);
        router.post("/api/friend/black", this::handleAddBlack);
        router.post("/api/friend/unblack", this::handleRemoveBlack);
        router.get("/api/friend/blacklist", this::handleBlackList);
    }

    private Object handleApply(FullHttpRequest req, ChannelHandlerContext ctx) {
        Map<String, Object> body = parseJsonBody(req);
        String fromUserId = str(body, "fromUserId");
        String toUserId = str(body, "toUserId");
        String reqMsg = str(body, "reqMsg", "");
        if (fromUserId == null || toUserId == null) {
            throw new ImException(ImErrorCode.BAD_REQUEST, "fromUserId and toUserId are required");
        }
        friendManager.applyAddFriend(fromUserId, toUserId, reqMsg);
        log.debug("FRIEND_APPLY: {} -> {}", fromUserId, toUserId);
        return Map.of("status", "OK");
    }

    private Object handleApprove(FullHttpRequest req, ChannelHandlerContext ctx) {
        Map<String, Object> body = parseJsonBody(req);
        String userId = str(body, "userId");
        String fromUserId = str(body, "fromUserId");
        boolean agreed = bool(body, "agreed", true);
        String handleMsg = str(body, "handleMsg", "");
        if (userId == null || fromUserId == null) {
            throw new ImException(ImErrorCode.BAD_REQUEST, "userId and fromUserId are required");
        }
        friendManager.respondFriendApply(userId, fromUserId, handleMsg, agreed);
        log.debug("FRIEND_APPROVE: userId={} fromUserId={} agreed={}", userId, fromUserId, agreed);
        return Map.of("status", "OK");
    }

    private Object handleRemove(FullHttpRequest req, ChannelHandlerContext ctx) {
        Map<String, Object> body = parseJsonBody(req);
        String userId = str(body, "userId");
        String friendUserId = str(body, "friendUserId");
        if (userId == null || friendUserId == null) {
            throw new ImException(ImErrorCode.BAD_REQUEST, "userId and friendUserId are required");
        }
        friendManager.deleteFriend(userId, friendUserId);
        log.debug("FRIEND_REMOVE: owner={} friend={}", userId, friendUserId);
        return Map.of("status", "OK");
    }

    private Object handleList(FullHttpRequest req, ChannelHandlerContext ctx) {
        Map<String, String> params = parseQuery(req);
        String userId = params.get("userId");
        if (userId == null) throw new ImException(ImErrorCode.BAD_REQUEST, "userId is required");
        List<FriendInformation> friends = friendManager.getFriendList(userId);
        return Map.of("userId", userId, "friends", friends, "count", friends.size());
    }

    private Object handleAddBlack(FullHttpRequest req, ChannelHandlerContext ctx) {
        Map<String, Object> body = parseJsonBody(req);
        String userId = str(body, "userId");
        String blockedUserId = str(body, "blockedUserId");
        if (userId == null || blockedUserId == null) {
            throw new ImException(ImErrorCode.BAD_REQUEST, "userId and blockedUserId are required");
        }
        friendManager.addBlack(userId, blockedUserId);
        return Map.of("status", "OK");
    }

    private Object handleRemoveBlack(FullHttpRequest req, ChannelHandlerContext ctx) {
        Map<String, Object> body = parseJsonBody(req);
        String userId = str(body, "userId");
        String blockedUserId = str(body, "blockedUserId");
        if (userId == null || blockedUserId == null) {
            throw new ImException(ImErrorCode.BAD_REQUEST, "userId and blockedUserId are required");
        }
        friendManager.removeBlack(userId, blockedUserId);
        return Map.of("status", "OK");
    }

    private Object handleBlackList(FullHttpRequest req, ChannelHandlerContext ctx) {
        Map<String, String> params = parseQuery(req);
        String userId = params.get("userId");
        if (userId == null) throw new ImException(ImErrorCode.BAD_REQUEST, "userId is required");
        return Map.of("userId", userId, "blacklist", friendManager.getBlackList(userId));
    }
}
