package com.im.core.call;

import java.util.List;

public interface SingleCallStateStore {

    SingleCallSession getByRoom(String roomId);

    SingleCallSession getActiveByUser(String userId);

    SingleCallSession createIfUsersIdle(SingleCallSession session);

    TerminalSignalIntent getPendingTerminalSignal(String roomId);

    /**
     * Finds the durable terminal request record by its send idempotency identity.
     * Implementations retain this record after delivery acknowledgement so a
     * changed retry cannot bypass the message idempotency cache.
     */
    default TerminalSignalIntent getTerminalSignalByRequest(String actorId, String peerUserId, String clientMsgId) {
        return null;
    }

    /**
     * Atomically applies a terminal call transition or records an ICE request,
     * then resumes an already-recorded equivalent intent.
     */
    boolean transitionTerminalSignal(TerminalSignalIntent intent);

    /** Removes the pending intent only when every identity field still matches. */
    boolean acknowledgeTerminalSignal(TerminalSignalIntent intent);

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

    /**
     * Removes the durable timeout-delivery work item after both deterministic
     * recipient effects have been published or recorded for compensation.
     */
    default void acknowledgeTimeoutDelivery(String roomId) {
    }

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
