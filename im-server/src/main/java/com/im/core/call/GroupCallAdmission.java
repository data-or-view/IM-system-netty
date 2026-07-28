package com.im.core.call;

/** Result of atomically admitting a participant to a group call. */
public record GroupCallAdmission(GroupCallSession session, boolean joined, boolean full) {
}
