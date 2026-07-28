package com.im.core.call;

/** Result of atomically reserving a group call room. */
public record GroupCallReservation(GroupCallSession session, boolean created) {
}
