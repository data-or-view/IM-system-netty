package com.im.core.call;

import java.util.List;

public record GroupCallSession(String groupId,
                               String roomId,
                               String callType,
                               String initiatorUserId,
                               String sfuEndpoint,
                               long startedAt,
                               long updatedAt,
                               int participantCount,
                               List<GroupCallParticipant> participants,
                               boolean ended) {

    public GroupCallSession {
        participants = participants != null ? List.copyOf(participants) : List.of();
    }

    public GroupCallSession withParticipantCount(int participantCount) {
        return new GroupCallSession(groupId, roomId, callType, initiatorUserId, sfuEndpoint,
                startedAt, System.currentTimeMillis(), participantCount, participants, ended);
    }

    public GroupCallSession withParticipants(List<GroupCallParticipant> participants) {
        return new GroupCallSession(groupId, roomId, callType, initiatorUserId, sfuEndpoint,
                startedAt, System.currentTimeMillis(), participants != null ? participants.size() : 0,
                participants, ended);
    }

    public GroupCallSession markEnded() {
        return new GroupCallSession(groupId, roomId, callType, initiatorUserId, sfuEndpoint,
                startedAt, System.currentTimeMillis(), participantCount, participants, true);
    }
}
