package com.im.core.usecase;

public record SendMessageResult(String conversationId, long seq, String responseType) {}
