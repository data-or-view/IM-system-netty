package com.im.core.call;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
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
            session.participants.add(userId);
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
        private final Set<String> participants = new HashSet<>();

        private MutableSession(GroupCallSession base) {
            this.base = base;
            participants.add(base.initiatorUserId());
        }

        private GroupCallSession snapshot() {
            return base.withParticipantCount(participants.size());
        }
    }
}
