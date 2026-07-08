package com.im.core.observability;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StructuredLogTest {

    @Test
    void formatsStableKeyValueEventAndSkipsBlankValues() {
        String line = StructuredLog.event(LogEvents.REQUEST_COMPLETED,
                LogFields.REQUEST_ID, "req-1",
                LogFields.OPERATION, "chat.send",
                LogFields.CLIENT_IP, "203.0.113.10",
                LogFields.ERROR_CODE, null,
                LogFields.DETAIL, "contains spaces");

        assertEquals("event=im.request.completed requestId=req-1 operation=chat.send clientIp=203.0.113.10 detail=\"contains spaces\"", line);
    }
}
