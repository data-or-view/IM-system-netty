package com.im.core.call;

public interface GroupCallStateStore {

    GroupCallSession getActiveByGroup(String groupId);

    GroupCallReservation reserve(GroupCallSession session);

    GroupCallSession activate(String groupId, String roomId, String sfuEndpoint, long now);

    GroupCallAdmission admit(String groupId, String userId, int maxParticipants, long now);

    GroupCallSession removeParticipant(String groupId, String userId);

    GroupCallSession end(String groupId);
}
