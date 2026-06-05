package com.im.core.usecase;

import java.util.Set;

public record RevokeResult(String conversationId, long seq, String revokerId, Set<String> targetUserIds) {}
