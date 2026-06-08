package com.im.api;

import com.im.common.exception.UnauthorizedException;

public final class RequestPreconditions {

    private RequestPreconditions() {
    }

    public static String requireUser(ApiRequest request) {
        String userId = request != null ? request.currentUserId() : null;
        if (userId == null || userId.isBlank()) {
            throw new UnauthorizedException("not authenticated");
        }
        return userId;
    }
}
