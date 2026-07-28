package com.im.core.call;

import com.im.api.ICallManager;
import com.im.api.IGroupManager;
import com.im.api.RoomInformation;
import com.im.common.exception.ForbiddenException;
import com.im.common.exception.ValidationException;
import com.im.common.id.IdGenerator;

public class GroupCallManager {

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
        GroupCallSession requested = new GroupCallSession(groupId, roomId, normalizedCallType,
                operatorId, "", now, now, 1,
                java.util.List.of(new GroupCallParticipant(operatorId, now)), false);
        GroupCallReservation reservation = stateStore.reserve(requested);
        if (!reservation.created()) {
            return reservation.session();
        }
        RoomInformation room = callManager.createRoom(operatorId, null, reservation.session().roomId());
        return stateStore.activate(groupId, reservation.session().roomId(), room.getSfuEndpoint(), now);
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
        return stateStore.removeParticipant(groupId, userId);
    }

    public GroupCallSession end(String operatorId, String groupId) {
        requireMember(groupId, operatorId);
        GroupCallSession active = stateStore.getActiveByGroup(groupId);
        if (active == null) return null;
        if (!canEnd(operatorId, groupId, active)) {
            throw new ForbiddenException("only group owner, admin or initiator can end group call");
        }
        return stateStore.end(groupId);
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
