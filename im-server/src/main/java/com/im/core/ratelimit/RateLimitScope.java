package com.im.core.ratelimit;

enum RateLimitScope {
    IP("ip"),
    LOGIN_USER("login_user"),
    USER("user"),
    USER_CONVERSATION("user_conversation"),
    USER_GROUP("user_group"),
    USER_TARGET("user_target"),
    GLOBAL("global");

    private final String keyPart;

    RateLimitScope(String keyPart) {
        this.keyPart = keyPart;
    }

    String keyPart() {
        return keyPart;
    }
}
