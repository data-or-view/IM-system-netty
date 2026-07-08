package com.im.core.observability;

import com.im.api.Message;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MessageObservabilityTest {

    @Test
    void bindsMessageAndOriginContextIntoMdcThenRestoresPreviousValues() {
        Message message = Message.createSingle("u1", "u2", "single_u1_u2", 101, "{}", 7);
        message.setMessageId("client-msg-1");
        message.putMeta(MessageObservability.META_REQUEST_ID, "req-1");
        message.putMeta(MessageObservability.META_TRACE_ID, "trace-1");
        message.putMeta(MessageObservability.META_CLIENT_MSG_ID, "client-msg-1");
        message.putMeta(MessageObservability.META_ORIGIN_OPERATION, "chat.send");
        MDC.put(LogFields.MDC_REQUEST_ID, "outer-req");

        try (MessageObservability.Scope ignored = MessageObservability.bind("deliver", message)) {
            assertEquals("req-1", MDC.get(LogFields.MDC_REQUEST_ID));
            assertEquals("trace-1", MDC.get(LogFields.MDC_TRACE_ID));
            assertEquals("client-msg-1", MDC.get(LogFields.MDC_CLIENT_MSG_ID));
            assertEquals("client-msg-1", MDC.get(LogFields.MDC_MESSAGE_ID));
            assertEquals("single_u1_u2", MDC.get(LogFields.MDC_CONVERSATION_ID));
            assertEquals("chat.send", MDC.get(LogFields.MDC_OPERATION));
        }

        assertEquals("outer-req", MDC.get(LogFields.MDC_REQUEST_ID));
        assertNull(MDC.get(LogFields.MDC_TRACE_ID));
        assertNull(MDC.get(LogFields.MDC_CLIENT_MSG_ID));
        MDC.clear();
    }
}
