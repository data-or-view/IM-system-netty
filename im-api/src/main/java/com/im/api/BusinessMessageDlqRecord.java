package com.im.api;

public record BusinessMessageDlqRecord(long id,
                                       String topic,
                                       String messageId,
                                       String payloadJson,
                                       int attemptCount) {
}
