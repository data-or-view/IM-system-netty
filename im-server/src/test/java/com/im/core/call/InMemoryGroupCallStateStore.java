package com.im.core.call;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

class InMemoryGroupCallStateStore implements GroupCallStateStore {

    private final Map<String, MutableSession> sessions = new ConcurrentHashMap<>();

    @Override
    public GroupCallSession getActiveByGroup(String groupId) {
        synchronized (sessions) {
            MutableSession session = sessions.get(groupId);
            return session != null && session.active ? session.snapshot() : null;
        }
    }

    @Override
    public GroupCallReservation reserve(String groupId, String roomId, String callType,
                                        String initiatorUserId, long now) {
        synchronized (sessions) {
            MutableSession existing = sessions.get(groupId);
            if (existing != null) {
                return new GroupCallReservation(existing.snapshot(), false, existing.active);
            }
            GroupCallSession session = new GroupCallSession(groupId, roomId, callType,
                    initiatorUserId, "", now, now, 1,
                    List.of(new GroupCallParticipant(initiatorUserId, now)), false);
            MutableSession created = new MutableSession(session);
            sessions.put(groupId, created);
            return new GroupCallReservation(created.snapshot(), true, false);
        }
    }

    @Override
    public GroupCallSession activate(String groupId, String roomId, String sfuEndpoint, long now) {
        synchronized (sessions) {
            MutableSession session = sessions.get(groupId);
            if (session == null || !session.base.roomId().equals(roomId)) return null;
            session.base = new GroupCallSession(groupId, roomId, session.base.callType(),
                    session.base.initiatorUserId(), sfuEndpoint, session.base.startedAt(), now,
                    session.participants.size(), List.of(), false);
            session.active = true;
            return session.snapshot();
        }
    }

    @Override
    public GroupCallAdmission admit(String groupId, String userId, int maxParticipants, long now) {
        synchronized (sessions) {
            MutableSession session = sessions.get(groupId);
            if (session == null || !session.active) return new GroupCallAdmission(null, false, false);
            if (session.participants.containsKey(userId)) {
                return new GroupCallAdmission(session.snapshot(), true, false);
            }
            if (maxParticipants > 0 && session.participants.size() >= maxParticipants) {
                return new GroupCallAdmission(session.snapshot(), false, true);
            }
            session.participants.put(userId, now);
            return new GroupCallAdmission(session.snapshot(), true, false);
        }
    }

    @Override
    public GroupCallSession removeParticipant(String groupId, String userId,
                                              String expectedRoomId, long now) {
        synchronized (sessions) {
            MutableSession session = sessions.get(groupId);
            if (session == null || !session.active || !session.base.roomId().equals(expectedRoomId)) return null;
            session.participants.remove(userId);
            if (session.participants.isEmpty()) {
                sessions.remove(groupId);
                return session.snapshot().markEnded();
            }
            return session.snapshot();
        }
    }

    @Override
    public GroupCallSession end(String groupId, String expectedRoomId, long now) {
        synchronized (sessions) {
            MutableSession session = sessions.get(groupId);
            if (session == null || !session.base.roomId().equals(expectedRoomId)) return null;
            sessions.remove(groupId);
            return session != null ? session.snapshot().markEnded() : null;
        }
    }

    private static final class MutableSession {
        private GroupCallSession base;
        private boolean active;
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
