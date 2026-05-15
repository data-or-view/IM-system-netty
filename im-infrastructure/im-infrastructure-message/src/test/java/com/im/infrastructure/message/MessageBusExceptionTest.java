package com.im.infrastructure.message;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MessageBusExceptionTest {

    @Test
    void exceptionWithMessage() {
        MessageBusException e = new MessageBusException("connection failed");
        assertEquals("connection failed", e.getMessage());
    }

    @Test
    void exceptionWithCause() {
        Throwable cause = new RuntimeException("timeout");
        MessageBusException e = new MessageBusException("publish failed", cause);
        assertEquals("publish failed", e.getMessage());
        assertSame(cause, e.getCause());
    }
}
