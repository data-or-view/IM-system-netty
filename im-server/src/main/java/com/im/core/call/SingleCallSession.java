package com.im.core.call;

public record SingleCallSession(String roomId,
                                String callerId,
                                String calleeId,
                                String callType,
                                String status,
                                String sfuEndpoint,
                                long startedAt,
                                long acceptedAt,
                                long deadlineAt) {

    public static final String STATUS_RINGING = "RINGING";
    public static final String STATUS_ACCEPTED = "ACCEPTED";
    public static final String STATUS_ENDED = "ENDED";
    public static final String STATUS_TIMED_OUT = "TIMED_OUT";

    public SingleCallSession(String roomId, String callerId, String calleeId, String callType,
                             String status, String sfuEndpoint, long startedAt, long acceptedAt) {
        this(roomId, callerId, calleeId, callType, status, sfuEndpoint, startedAt, acceptedAt, startedAt);
    }

    public SingleCallSession accept(long acceptedAt) {
        return new SingleCallSession(roomId, callerId, calleeId, callType, STATUS_ACCEPTED,
                sfuEndpoint, startedAt, acceptedAt, deadlineAt);
    }

    public SingleCallSession end() {
        return new SingleCallSession(roomId, callerId, calleeId, callType, STATUS_ENDED,
                sfuEndpoint, startedAt, acceptedAt, deadlineAt);
    }

    public SingleCallSession timedOut() {
        return new SingleCallSession(roomId, callerId, calleeId, callType, STATUS_TIMED_OUT,
                sfuEndpoint, startedAt, acceptedAt, deadlineAt);
    }
}
