package com.im.core.handler.unified;

import com.im.api.ApiRequest;
import com.im.api.IFriendManager;
import com.im.api.IUserManager;
import com.im.api.RequestPreconditions;
import com.im.api.RequestHandler;
import com.im.api.UserInformation;
import com.im.common.exception.NotFoundException;
import com.im.common.validation.Preconditions;
import com.im.core.security.UserProfileSanitizer;
import com.im.core.usecase.RegisterResult;
import com.im.core.usecase.RegisterUseCase;
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
    private final IFriendManager friendManager;
    private final RegisterUseCase registerUseCase;

    public UserHandler(IUserManager userManager) {
        this(userManager, null, new RegisterUseCase(userManager));
    }

    public UserHandler(IUserManager userManager, RegisterUseCase registerUseCase) {
        this(userManager, null, registerUseCase);
    }

    public UserHandler(IUserManager userManager, IFriendManager friendManager, RegisterUseCase registerUseCase) {
        this.userManager = userManager;
        this.friendManager = friendManager;
        this.registerUseCase = registerUseCase;
    }

    @Override
    public Object handle(ApiRequest req) {
        return switch (req.operation()) {
            case "user.register" -> handleRegister(req);
            case "user.me" -> handleMe(req);
            case "user.info" -> handleInfo(req);
            case "user.search" -> handleSearch(req);
            case "user.update" -> handleUpdate(req);
            default -> throw new NotFoundException("unsupported: " + req.operation());
        };
    }

    private Object handleRegister(ApiRequest req) {
        String nickname = req.getString("nickname");
        String faceUrl = req.getString("faceUrl", "");
        String password = req.getString("password", "");

        RegisterResult result = registerUseCase.execute(null, nickname, faceUrl, password);
        return Map.of("userId", result.userId(), "nickname", result.nickname(), "status", "OK");
    }

    private Object handleMe(ApiRequest req) {
        String userId = RequestPreconditions.requireUser(req);
        UserInformation info = userManager.getUserInformation(userId);
        if (info == null) throw new NotFoundException("user not found");
        return UserProfileSanitizer.self(info);
    }

    private Object handleInfo(ApiRequest req) {
        String viewerId = RequestPreconditions.requireUser(req);
        String userId = req.getString("userId");
        userId = Preconditions.requireText(userId, "userId");
        UserInformation info = userManager.getUserInformation(userId);
        if (info == null) throw new NotFoundException("user not found");
        return profileForViewer(viewerId, info);
    }

    private Object handleSearch(ApiRequest req) {
        RequestPreconditions.requireUser(req);
        String keyword = req.getString("keyword");
        keyword = Preconditions.requireText(keyword, "keyword");
        int limit = req.getInt("limit", 20);
        List<UserInformation> users = userManager.searchUsers(keyword.trim(), limit);
        List<Map<String, Object>> sanitized = users.stream()
                .map(UserProfileSanitizer::publicView)
                .toList();
        return Map.of("users", sanitized, "count", sanitized.size());
    }

    private Object handleUpdate(ApiRequest req) {
        String userId = RequestPreconditions.requireUser(req);
        userManager.updateUserInformation(userId, req.getString("nickname"),
                req.getString("faceUrl"), req.getString("ex"),
                req.getInt("globalRecvMsgOpt", -1));
        return Map.of("userId", userId, "status", "OK");
    }

    private Map<String, Object> profileForViewer(String viewerId, UserInformation info) {
        if (viewerId.equals(info.getUserId())) {
            return UserProfileSanitizer.self(info);
        }
        if (friendManager != null && friendManager.isFriend(viewerId, info.getUserId())) {
            return UserProfileSanitizer.friend(info);
        }
        return UserProfileSanitizer.publicView(info);
    }
}
