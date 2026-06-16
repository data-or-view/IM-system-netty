package com.im.api;

public record MessageSendFailureRecord(long id,
                                       String topic,
                                       String messageId,
                                       String payloadJson,
                                       int attemptCount) {
}
