package com.im.core.call;

import java.util.List;

public interface SingleCallStateStore {

    SingleCallSession getByRoom(String roomId);

    SingleCallSession getActiveByUser(String userId);

    SingleCallSession createIfUsersIdle(SingleCallSession session);

    SingleCallSession accept(String roomId);

    default SingleCallSession acceptBy(String roomId, String actorId) {
        SingleCallSession session = getByRoom(roomId);
        if (session == null || !isParticipant(session, actorId)) {
            return null;
        }
        return accept(roomId);
    }

    SingleCallSession timeoutIfRinging(String roomId);

    List<SingleCallSession> claimExpiredRinging(long nowEpochMillis, int limit);

    SingleCallSession end(String roomId);

    default SingleCallSession endBy(String roomId, String actorId) {
        SingleCallSession session = getByRoom(roomId);
        if (session == null || !isParticipant(session, actorId)) {
            return null;
        }
        return end(roomId);
    }

    private static boolean isParticipant(SingleCallSession session, String actorId) {
        return actorId != null && (actorId.equals(session.callerId()) || actorId.equals(session.calleeId()));
    }
}
