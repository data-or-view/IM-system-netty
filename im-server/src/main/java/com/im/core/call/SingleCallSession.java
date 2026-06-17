package com.im.core.call;

public record SingleCallSession(String roomId,
                                String callerId,
                                String calleeId,
                                String callType,
                                String status,
                                String sfuEndpoint,
                                long startedAt,
                                long acceptedAt) {

    public static final String STATUS_RINGING = "RINGING";
    public static final String STATUS_ACCEPTED = "ACCEPTED";
    public static final String STATUS_ENDED = "ENDED";

    public SingleCallSession accept(long acceptedAt) {
        return new SingleCallSession(roomId, callerId, calleeId, callType, STATUS_ACCEPTED,
                sfuEndpoint, startedAt, acceptedAt);
    }

    public SingleCallSession end() {
        return new SingleCallSession(roomId, callerId, calleeId, callType, STATUS_ENDED,
                sfuEndpoint, startedAt, acceptedAt);
    }
}
