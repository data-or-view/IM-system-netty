package com.im.core.call;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

class InMemoryGroupCallStateStore implements GroupCallStateStore {

    private final Map<String, MutableSession> sessions = new ConcurrentHashMap<>();

    @Override
    public GroupCallSession getActiveByGroup(String groupId) {
        MutableSession session = sessions.get(groupId);
        return session != null ? session.snapshot() : null;
    }

    @Override
    public GroupCallSession createIfAbsent(GroupCallSession session) {
        MutableSession existing = sessions.putIfAbsent(session.groupId(), new MutableSession(session));
        return existing != null ? existing.snapshot() : session;
    }

    @Override
    public GroupCallSession addParticipant(String groupId, String userId) {
        MutableSession session = sessions.get(groupId);
        if (session == null) return null;
        synchronized (session) {
            session.participants.putIfAbsent(userId, System.currentTimeMillis());
            return session.snapshot();
        }
    }

    @Override
    public GroupCallSession removeParticipant(String groupId, String userId) {
        MutableSession session = sessions.get(groupId);
        if (session == null) return null;
        synchronized (session) {
            session.participants.remove(userId);
            if (session.participants.isEmpty()) {
                sessions.remove(groupId);
                return session.snapshot().markEnded();
            }
            return session.snapshot();
        }
    }

    @Override
    public GroupCallSession end(String groupId) {
        MutableSession session = sessions.remove(groupId);
        return session != null ? session.snapshot().markEnded() : null;
    }

    private static final class MutableSession {
        private final GroupCallSession base;
        private final Map<String, Long> participants = new LinkedHashMap<>();

        private MutableSession(GroupCallSession base) {
            this.base = base;
            if (!base.participants().isEmpty()) {
                for (GroupCallParticipant participant : base.participants()) {
                    participants.put(participant.userId(), participant.joinedAt());
                }
            } else {
                participants.put(base.initiatorUserId(), base.startedAt());
            }
        }

        private GroupCallSession snapshot() {
            List<GroupCallParticipant> list = participants.entrySet().stream()
                    .map(entry -> new GroupCallParticipant(entry.getKey(), entry.getValue()))
                    .toList();
            return base.withParticipants(list);
        }
    }
}
