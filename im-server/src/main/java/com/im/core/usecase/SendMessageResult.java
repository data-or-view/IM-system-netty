package com.im.core.usecase;

public record SendMessageResult(String messageId, String conversationId, long seq, String status) {
}
