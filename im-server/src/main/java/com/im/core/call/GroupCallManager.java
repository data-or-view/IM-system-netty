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
        GroupCallSession existing = stateStore.getActiveByGroup(groupId);
        if (existing != null) return existing;

        String roomId = IdGenerator.roomId();
        RoomInformation room = callManager.createRoom(operatorId, null, roomId);
        GroupCallSession session = new GroupCallSession(groupId, room.getRoomId(), normalizedCallType,
                operatorId, room.getSfuEndpoint(), System.currentTimeMillis(), 1, false);
        return stateStore.createIfAbsent(session);
    }

    public GroupCallJoinResult join(String userId, String groupId) {
        requireMember(groupId, userId);
        GroupCallSession active = stateStore.getActiveByGroup(groupId);
        if (active == null) {
            throw new ValidationException("no active group call");
        }
        if (maxParticipants > 0 && active.participantCount() >= maxParticipants) {
            throw new ForbiddenException("group call is full");
        }
        GroupCallSession joined = stateStore.addParticipant(groupId, userId);
        String token = callManager.issueToken(userId, active.roomId());
        return new GroupCallJoinResult(joined, token, callManager.getSfuEndpoint());
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
