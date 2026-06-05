package com.im.core.handler.unified;

import com.im.api.ApiRequest;
import com.im.api.RequestHandler;
import com.im.core.usecase.RegisterResult;
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
        String nickname = req.getString("nickname");
        String faceUrl = req.getString("faceUrl", "");
        String password = req.getString("password", "");

        RegisterResult result = registerUseCase.execute(null, nickname, faceUrl, password);

        return Map.of("status", "OK",
                "userId", result.userId(),
                "nickname", result.nickname() != null ? result.nickname() : "",
                "faceUrl", result.faceUrl() != null ? result.faceUrl() : "");
    }
}
