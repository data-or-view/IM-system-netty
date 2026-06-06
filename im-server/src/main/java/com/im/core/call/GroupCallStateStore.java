package com.im.core.call;

public interface GroupCallStateStore {

    GroupCallSession getActiveByGroup(String groupId);

    GroupCallSession createIfAbsent(GroupCallSession session);

    GroupCallSession addParticipant(String groupId, String userId);

    GroupCallSession removeParticipant(String groupId, String userId);

    GroupCallSession end(String groupId);
}
