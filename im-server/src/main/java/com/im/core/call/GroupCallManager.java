package com.im.core.call;

import com.im.api.ICallManager;
import com.im.api.IGroupManager;
import com.im.api.RoomInformation;
import com.im.common.exception.ForbiddenException;
import com.im.common.exception.ValidationException;
import com.im.common.id.IdGenerator;

import java.util.concurrent.locks.LockSupport;

public class GroupCallManager {

    private static final int CREATION_POLL_ATTEMPTS = 25;
    private static final long CREATION_POLL_NANOS = 10_000_000L;

    private final IGroupManager groupManager;
    private final ICallManager callManager;
    private final GroupCallStateStore stateStore;
    private final int maxParticipants;

    public GroupCallManager(IGroupManager groupManager, ICallManager callManager,
                            GroupCallStateStore stateStore, int maxParticipants) {
        this.groupManager = groupManager;
        this.callManager = callManager;
        this.stateStore = stateStore;
        this.maxParticipants = maxParticipants;
    }

    public GroupCallSession start(String operatorId, String groupId, String callType) {
        requireMember(groupId, operatorId);
        String normalizedCallType = normalizeCallType(callType);
        String roomId = IdGenerator.roomId();
        long now = System.currentTimeMillis();
        GroupCallReservation reservation = stateStore.reserve(
                groupId, roomId, normalizedCallType, operatorId, now);
        if (!reservation.created() && !reservation.active()) {
            reservation = awaitReservation(groupId, roomId, normalizedCallType, operatorId);
        }
        if (!reservation.created()) {
            if (!reservation.active()) {
                throw new ValidationException("group call is being created");
            }
            return reservation.session();
        }
        if (!stateStore.validateCreationOwner(groupId, reservation.session().roomId(),
                reservation.creationEpoch(), System.currentTimeMillis())) {
            throw new ValidationException("group call creation was superseded");
        }
        RoomInformation room = callManager.createRoom(operatorId, null, reservation.session().roomId());
        GroupCallSession activated = stateStore.activate(groupId, reservation.session().roomId(),
                reservation.creationEpoch(), room.getSfuEndpoint(), System.currentTimeMillis());
        if (activated == null) {
            throw new ValidationException("group call creation was superseded");
        }
        return activated;
    }

    private GroupCallReservation awaitReservation(String groupId, String roomId,
                                                   String callType, String operatorId) {
        GroupCallReservation reservation = null;
        for (int attempt = 0; attempt < CREATION_POLL_ATTEMPTS; attempt++) {
            LockSupport.parkNanos(CREATION_POLL_NANOS);
            reservation = stateStore.reserve(groupId, roomId, callType,
                    operatorId, System.currentTimeMillis());
            if (reservation.created() || reservation.active()) return reservation;
        }
        return reservation;
    }

    public GroupCallJoinResult join(String userId, String groupId) {
        requireMember(groupId, userId);
        GroupCallAdmission admission = stateStore.admit(groupId, userId, maxParticipants, System.currentTimeMillis());
        if (admission.session() == null) {
            throw new ValidationException("no active group call");
        }
        if (admission.full()) {
            throw new ForbiddenException("group call is full");
        }
        if (!admission.joined()) {
            throw new ValidationException("group call is not accepting participants");
        }
        String token = callManager.issueToken(userId, admission.session().roomId());
        return new GroupCallJoinResult(admission.session(), token, callManager.getSfuEndpoint());
    }

    public GroupCallSession leave(String userId, String groupId) {
        requireMember(groupId, userId);
        GroupCallSession active = stateStore.getActiveByGroup(groupId);
        if (active == null) return null;
        return stateStore.removeParticipant(groupId, userId, active.roomId(), System.currentTimeMillis());
    }

    public GroupCallSession end(String operatorId, String groupId) {
        requireMember(groupId, operatorId);
        GroupCallSession active = stateStore.getActiveByGroup(groupId);
        if (active == null) return null;
        if (!canEnd(operatorId, groupId, active)) {
            throw new ForbiddenException("only group owner, admin or initiator can end group call");
        }
        return stateStore.end(groupId, active.roomId(), System.currentTimeMillis());
    }

    public GroupCallSession active(String operatorId, String groupId) {
        requireMember(groupId, operatorId);
        return stateStore.getActiveByGroup(groupId);
    }

    private void requireMember(String groupId, String userId) {
        if (groupId == null || groupId.isBlank()) throw new ValidationException("groupId is required");
        if (userId == null || userId.isBlank()) throw new ValidationException("userId is required");
        if (!groupManager.isMember(groupId, userId)) {
            throw new ForbiddenException("not a group member");
        }
    }

    private boolean canEnd(String operatorId, String groupId, GroupCallSession active) {
        if (operatorId.equals(active.initiatorUserId())) return true;
        String role = groupManager.getRole(groupId, operatorId);
        return "owner".equals(role) || "admin".equals(role);
    }

    private static String normalizeCallType(String callType) {
        if (callType == null || callType.isBlank() || "video".equalsIgnoreCase(callType)) return "video";
        if ("voice".equalsIgnoreCase(callType)) return "voice";
        throw new ValidationException("unsupported group call type");
    }
}
