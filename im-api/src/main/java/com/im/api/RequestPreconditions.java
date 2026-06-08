package com.im.api;

import com.im.common.exception.UnauthorizedException;

public final class RequestPreconditions {

    private RequestPreconditions() {
    }

    /**
     * 认证校验放在 api 模块，避免各协议 handler 各自约定“未登录”的判断口径。
     * 只要后续入口继续使用 ApiRequest，就能保持 HTTP/WS 的认证错误语义一致。
     */
    public static String requireUser(ApiRequest request) {
        String userId = request != null ? request.currentUserId() : null;
        if (userId == null || userId.isBlank()) {
            throw new UnauthorizedException("not authenticated");
        }
        return userId;
    }
}
