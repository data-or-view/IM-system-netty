package com.im.api;

public record TokenRefreshResult(String accessToken, String refreshToken) {
    public boolean hasNewRefreshToken() {
        return refreshToken != null && !refreshToken.isEmpty();
    }
}
