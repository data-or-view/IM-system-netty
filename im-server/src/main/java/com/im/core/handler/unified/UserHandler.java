package com.im.core.handler.unified;

import com.im.api.ApiRequest;
import com.im.api.IUserManager;
import com.im.api.RequestHandler;
import com.im.api.UserInformation;
import com.im.common.exception.UnauthorizedException;
import com.im.common.exception.ValidationException;
import com.im.common.exception.NotFoundException;
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
    private final RegisterUseCase registerUseCase;

    public UserHandler(IUserManager userManager) {
        this(userManager, new RegisterUseCase(userManager));
    }

    public UserHandler(IUserManager userManager, RegisterUseCase registerUseCase) {
        this.userManager = userManager;
        this.registerUseCase = registerUseCase;
    }

    @Override
    public Object handle(ApiRequest req) {
        return switch (req.operation()) {
            case "user.register" -> handleRegister(req);
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

    private Object handleInfo(ApiRequest req) {
        String userId = req.getString("userId");
        if (userId == null) throw new ValidationException("userId is required");
        UserInformation info = userManager.getUserInformation(userId);
        if (info == null) throw new NotFoundException("user not found");
        return info;
    }

    private Object handleSearch(ApiRequest req) {
        String keyword = req.getString("keyword");
        if (keyword == null || keyword.isBlank()) {
            throw new ValidationException("keyword is required");
        }
        int limit = req.getInt("limit", 20);
        List<UserInformation> users = userManager.searchUsers(keyword.trim(), limit);
        return Map.of("users", users, "count", users.size());
    }

    private Object handleUpdate(ApiRequest req) {
        String userId = req.currentUserId();
        if (userId == null) throw new UnauthorizedException("not authenticated");
        userManager.updateUserInformation(userId, req.getString("nickname"),
                req.getString("faceUrl"), req.getString("ex"),
                req.getInt("globalRecvMsgOpt", -1));
        return Map.of("userId", userId, "status", "OK");
    }
}
