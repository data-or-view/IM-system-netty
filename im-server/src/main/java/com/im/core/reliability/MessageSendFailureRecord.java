package com.im.core.reliability;

public record MessageSendFailureRecord(long id,
                                       String topic,
                                       String messageId,
                                       String payloadJson,
                                       int attemptCount) {
}
