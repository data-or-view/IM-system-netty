package com.im.bootstrap.http;

import com.im.api.ImErrorCode;
import com.im.api.ImException;
import com.im.api.IUserManager;
import com.im.api.UserInformation;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

import static com.im.bootstrap.http.HttpParamUtils.*;

/**
 * 用户域 REST 控制器。
 *
 * <p>处理 /api/user/* 路由：注册、查询、搜索、更新用户资料。</p>
 */
public class UserRestHandler implements RestController {

    private static final Logger log = LoggerFactory.getLogger(UserRestHandler.class);

    private final IUserManager userManager;

    public UserRestHandler(IUserManager userManager) {
        this.userManager = userManager;
    }

    @Override
    public void register(HttpRestHandler router) {
        router.post("/api/user/register", this::handleRegister);
        router.get("/api/user/info", this::handleInfo);
        router.get("/api/user/search", this::handleSearch);
        router.post("/api/user/update", this::handleUpdate);
    }

    private Object handleRegister(FullHttpRequest req, ChannelHandlerContext ctx) {
        Map<String, Object> body = parseJsonBody(req);
        String userId = str(body, "userId");
        String nickname = str(body, "nickname", userId);
        String faceUrl = str(body, "faceUrl", "");
        String password = str(body, "password", "");

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
            } catch (Exception ignored) {}
        }

        log.info("User registered: userId={}", userId);
        return Map.of("userId", userId, "nickname", nickname, "status", "OK");
    }

    private Object handleInfo(FullHttpRequest req, ChannelHandlerContext ctx) {
        Map<String, String> params = parseQuery(req);
        String userId = params.get("userId");
        if (userId == null) throw new ImException(ImErrorCode.BAD_REQUEST, "userId is required");
        UserInformation info = userManager.getUserInformation(userId);
        if (info == null) throw new ImException(ImErrorCode.NOT_FOUND, "user not found");
        return info;
    }

    private Object handleSearch(FullHttpRequest req, ChannelHandlerContext ctx) {
        Map<String, String> params = parseQuery(req);
        String keyword = params.get("keyword");
        if (keyword == null || keyword.isBlank()) {
            throw new ImException(ImErrorCode.BAD_REQUEST, "keyword is required");
        }
        int limit = intParam(params, "limit", 20);
        List<UserInformation> users = userManager.searchUsers(keyword.trim(), limit);
        return Map.of("users", users, "count", users.size());
    }

    private Object handleUpdate(FullHttpRequest req, ChannelHandlerContext ctx) {
        Map<String, Object> body = parseJsonBody(req);
        String userId = str(body, "userId");
        if (userId == null) throw new ImException(ImErrorCode.BAD_REQUEST, "userId is required");
        userManager.updateUserInformation(
                userId, str(body, "nickname"), str(body, "faceUrl"),
                str(body, "ex"), intObj(body, "globalRecvMsgOpt", -1));
        return Map.of("userId", userId, "status", "OK");
    }
}
