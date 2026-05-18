package com.im.core.handler.unified;

import com.im.api.ApiRequest;
import com.im.api.RequestHandler;
import com.im.common.enums.ImErrorCode;
import com.im.common.exception.ImException;
import com.im.core.usecase.RegisterUseCase;

import java.util.Map;

/**
 * 注册 handler（仅 WS）。
 */
public class RegisterHandler implements RequestHandler {

    private final RegisterUseCase registerUseCase;

    public RegisterHandler(RegisterUseCase registerUseCase) {
        this.registerUseCase = registerUseCase;
    }

    @Override
    public Object handle(ApiRequest req) {
        String userId = req.getString("userId");
        if (userId == null || userId.isBlank()) {
            throw new ImException(ImErrorCode.BAD_REQUEST, "userId is required");
        }

        String nickname = req.getString("nickname", userId);
        String faceUrl = req.getString("faceUrl", "");
        String password = req.getString("password", "");

        RegisterUseCase.RegisterResult result = registerUseCase.execute(userId, nickname, faceUrl, password);

        return Map.of("status", "OK",
                "userId", userId,
                "nickname", result.nickname() != null ? result.nickname() : "",
                "faceUrl", result.faceUrl() != null ? result.faceUrl() : "");
    }
}
