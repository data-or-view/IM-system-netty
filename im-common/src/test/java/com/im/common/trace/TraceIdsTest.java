package com.im.common.trace;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TraceIdsTest {

    @Test
    void generatesLowercaseThirtyTwoCharTraceId() {
        String traceId = TraceIds.next();

        assertEquals(32, traceId.length());
        assertTrue(traceId.matches("[0-9a-f]{32}"));
    }

    @Test
    void extractsTraceIdFromW3cTraceparent() {
        String traceId = TraceIds.fromTraceparent(
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");

        assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", traceId);
    }

    @Test
    void rejectsInvalidTraceparent() {
        assertNull(TraceIds.fromTraceparent("00-not-a-trace-id-00f067aa0ba902b7-01"));
        assertNull(TraceIds.fromTraceparent(null));
    }
}
