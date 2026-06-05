package com.im.core.usecase;

public record RegisterResult(String userId, String nickname, String faceUrl, boolean alreadyExists) {}
