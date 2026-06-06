package com.im.core.call;

public record GroupCallSession(String groupId,
                               String roomId,
                               String callType,
                               String initiatorUserId,
                               String sfuEndpoint,
                               long startedAt,
                               int participantCount,
                               boolean ended) {

    public GroupCallSession withParticipantCount(int participantCount) {
        return new GroupCallSession(groupId, roomId, callType, initiatorUserId, sfuEndpoint,
                startedAt, participantCount, ended);
    }

    public GroupCallSession markEnded() {
        return new GroupCallSession(groupId, roomId, callType, initiatorUserId, sfuEndpoint,
                startedAt, participantCount, true);
    }
}
