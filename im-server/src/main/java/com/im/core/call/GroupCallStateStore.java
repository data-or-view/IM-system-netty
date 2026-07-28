package com.im.core.call;

public interface GroupCallStateStore {

    GroupCallSession getActiveByGroup(String groupId);

    GroupCallReservation reserve(String groupId, String roomId, String callType,
                                 String initiatorUserId, long now);

    GroupCallSession activate(String groupId, String roomId, String sfuEndpoint, long now);

    GroupCallAdmission admit(String groupId, String userId, int maxParticipants, long now);

    GroupCallSession removeParticipant(String groupId, String userId, String expectedRoomId, long now);

    GroupCallSession end(String groupId, String expectedRoomId, long now);
}
