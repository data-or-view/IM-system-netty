package com.im.core.handler.unified;

import com.im.api.ApiRequest;
import com.im.api.IUserManager;
import com.im.api.RequestHandler;
import com.im.api.UserInformation;
import com.im.common.enums.ImErrorCode;
import com.im.common.exception.ImException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * 用户域 handler：注册、查询、搜索、更新资料。
 *
 * <p>合并 WS {@code UserSearchHandler} + HTTP {@code UserRestHandler}。</p>
 */
public class UserHandler implements RequestHandler {

    private static final Logger log = LoggerFactory.getLogger(UserHandler.class);

    private final IUserManager userManager;

    public UserHandler(IUserManager userManager) {
        this.userManager = userManager;
    }

    @Override
    public Object handle(ApiRequest req) {
        return switch (req.operation()) {
            case "user.register" -> handleRegister(req);
            case "user.info" -> handleInfo(req);
            case "user.search" -> handleSearch(req);
            case "user.update" -> handleUpdate(req);
            default -> throw new ImException(ImErrorCode.NOT_FOUND, "unsupported: " + req.operation());
        };
    }

    private Object handleRegister(ApiRequest req) {
        String userId = req.getString("userId");
        String nickname = req.getString("nickname", userId);
        String faceUrl = req.getString("faceUrl", "");
        String password = req.getString("password", "");

        if (userId == null || userId.isBlank()) {
            throw new ImException(ImErrorCode.BAD_REQUEST, "userId is required");
        }

        boolean exists = false;
        try {
            var existing = userManager.getUserInformation(userId);
            exists = existing != null;
        } catch (ImException e) {
            exists = false;
        }

        if (!exists) {
            userManager.register(userId, nickname, faceUrl, null);
        }
        if (!password.isBlank()) {
            try {
                var user = userManager.getUserInformation(userId);
                if (user != null && user.getPassword() == null) {
                    user.setPassword(password);
                }
            } catch (Exception e) {
                log.warn("Failed to set password for {}: {}", userId, e.getMessage());
            }
        }
        return Map.of("userId", userId, "nickname", nickname, "status", "OK");
    }

    private Object handleInfo(ApiRequest req) {
        String userId = req.getString("userId");
        if (userId == null) throw new ImException(ImErrorCode.BAD_REQUEST, "userId is required");
        UserInformation info = userManager.getUserInformation(userId);
        if (info == null) throw new ImException(ImErrorCode.NOT_FOUND, "user not found");
        return info;
    }

    private Object handleSearch(ApiRequest req) {
        String keyword = req.getString("keyword");
        if (keyword == null || keyword.isBlank()) {
            throw new ImException(ImErrorCode.BAD_REQUEST, "keyword is required");
        }
        int limit = req.getInt("limit", 20);
        List<UserInformation> users = userManager.searchUsers(keyword.trim(), limit);
        return Map.of("users", users, "count", users.size());
    }

    private Object handleUpdate(ApiRequest req) {
        String userId = req.currentUserId();
        if (userId == null) throw new ImException(ImErrorCode.UNAUTHORIZED, "not authenticated");
        userManager.updateUserInformation(userId, req.getString("nickname"),
                req.getString("faceUrl"), req.getString("ex"),
                req.getInt("globalRecvMsgOpt", -1));
        return Map.of("userId", userId, "status", "OK");
    }
}
