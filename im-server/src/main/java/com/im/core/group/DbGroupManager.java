package com.im.core.group;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.im.api.*;
import com.im.core.db.MyBatisPlusFactory;
import com.im.core.db.entity.GroupEntity;
import com.im.core.db.entity.GroupMemberEntity;
import com.im.core.db.entity.GroupRequestEntity;
import com.im.core.db.entity.UserEntity;
import com.im.core.db.mapper.GroupMapper;
import com.im.core.db.mapper.GroupMemberMapper;
import com.im.core.db.mapper.GroupRequestMapper;
import com.im.core.db.mapper.UserMapper;
import com.im.core.sync.DbIncrementalSync;
import com.im.common.exception.ForbiddenException;
import com.im.common.exception.NotFoundException;
import com.im.common.exception.PersistenceExceptions;
import com.im.common.exception.ValidationException;
import com.im.common.retry.RetryConfig;
import com.im.common.retry.RetryExecutor;
import com.im.common.retry.RetryStrategies;
import org.apache.ibatis.session.SqlSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 数据库群组管理器。
 *
 * <p>基于 MyBatis-Plus，所有群组数据读写 {@code im_groups}、{@code im_group_members}、
 * {@code im_group_requests} 表。</p>
 */
public class DbGroupManager implements IGroupManager, GroupApplyPolicy.Gateway {

    private static final Logger log = LoggerFactory.getLogger(DbGroupManager.class);
    private static final RetryConfig CFG = RetryStrategies.DB_WRITE;
    public static final int GROUP_STATUS_DISBANDED = GroupStatus.DISBANDED.getCode();
    public static final int GROUP_STATUS_NORMAL = GroupStatus.NORMAL.getCode();
    private static final int GROUP_ROLE_ADMIN = GroupMemberRole.ADMIN.getCode();
    private static final int GROUP_ROLE_OWNER = GroupMemberRole.OWNER.getCode();

    private final RetryExecutor retryExecutor;
    private final DbIncrementalSync sync;
    private final GroupApplyPolicy groupApplyPolicy;

    public DbGroupManager(RetryExecutor retryExecutor) {
        this(retryExecutor, new DbIncrementalSync(retryExecutor));
    }

    public DbGroupManager(RetryExecutor retryExecutor, DbIncrementalSync sync) {
        this.retryExecutor = retryExecutor;
        this.sync = sync;
        this.groupApplyPolicy = new GroupApplyPolicy(this);
    }

    @Override
    public void createGroup(String groupId, String ownerId, String groupName, String faceUrl,
                            List<String> members, int groupType, int needVerification) {
        long now = System.currentTimeMillis();
        PersistenceExceptions.runDatabase("create group", () -> retryExecutor.execute(CFG, () -> {
        try (SqlSession session = MyBatisPlusFactory.openSession()) {
            GroupMapper groupMapper = session.getMapper(GroupMapper.class);
            GroupMemberMapper memberMapper = session.getMapper(GroupMemberMapper.class);

            GroupEntity entity = new GroupEntity();
            entity.setGroupId(groupId);
            entity.setGroupName(groupName);
            entity.setFaceUrl(faceUrl);
            entity.setOwnerUserId(ownerId);
            entity.setMemberCount(1);
            entity.setStatus(GROUP_STATUS_NORMAL);
            entity.setGroupType(groupType);
            entity.setNeedVerification(needVerification);
            entity.setCreatedAt(now);
            entity.setUpdatedAt(now);
            groupMapper.insert(entity);

            addMemberRecord(memberMapper, groupId, ownerId,
                    GroupMemberRole.OWNER.getCode(), ApplySource.SEARCH.getCode(), null, ownerId, now);

            if (members != null) {
                for (String m : members) {
                    if (!m.equals(ownerId)) {
                        addMemberRecord(memberMapper, groupId, m,
                                GroupMemberRole.MEMBER.getCode(), ApplySource.INVITE.getCode(), null, ownerId, now);
                        entity.setMemberCount(entity.getMemberCount() + 1);
                    }
                }
                groupMapper.updateById(entity);
            }
            session.commit();
            log.info("Group created: groupId={}, name={}, owner={}, members={}",
                    groupId, groupName, ownerId, members != null ? members.size() : 0);
        }
                    return null;
        }));

        // 记录增量同步
        sync.recordChange(ownerId, "group", groupId, "insert");
        sync.recordChange(groupId, "member", ownerId, "insert");
        if (members != null) {
            for (String m : members) {
                if (!m.equals(ownerId)) {
                    sync.recordChange(m, "group", groupId, "insert");
                    sync.recordChange(groupId, "member", m, "insert");
                }
            }
        }
    }

    @Override
    public GroupDisbandResult disbandGroup(String groupId, String operatorId) {
        GroupDisbandResult result = PersistenceExceptions.runDatabase("disband group", () -> retryExecutor.execute(CFG, () -> {
        try (SqlSession session = MyBatisPlusFactory.openSession()) {
            GroupMapper groupMapper = session.getMapper(GroupMapper.class);
            GroupMemberMapper memberMapper = session.getMapper(GroupMemberMapper.class);

            GroupEntity entity = groupMapper.selectById(groupId);
            if (entity == null) throw new NotFoundException("group not found");
            if (!entity.getOwnerUserId().equals(operatorId)) {
                log.warn("Only owner can disband group: groupId={}, operator={}", groupId, operatorId);
                throw new ForbiddenException("only group owner can disband group");
            }
            List<String> affectedMembers = memberMapper.selectList(
                    new LambdaQueryWrapper<GroupMemberEntity>()
                            .eq(GroupMemberEntity::getGroupId, groupId)
                            .select(GroupMemberEntity::getUserId)
            ).stream().map(GroupMemberEntity::getUserId).toList();
            entity.setStatus(GROUP_STATUS_DISBANDED);
            entity.setMemberCount(0);
            entity.setUpdatedAt(System.currentTimeMillis());
            groupMapper.updateById(entity);
            LambdaQueryWrapper<GroupMemberEntity> qw = new LambdaQueryWrapper<>();
            qw.eq(GroupMemberEntity::getGroupId, groupId);
            memberMapper.delete(qw);
            session.commit();
            log.info("Group disbanded: groupId={}", groupId);
            return new GroupDisbandResult(groupId, operatorId, entity.getGroupName(), affectedMembers);
        }
        }));

        for (String uid : result.getAffectedMemberIds()) {
            sync.recordChange(uid, "group", groupId, "delete");
            sync.recordChange(groupId, "member", uid, "delete");
        }
        return result;
    }

    @Override
    public void setGroupInformation(String groupId, String groupName, String notification,
                                     String introduction, String faceUrl, int needVerification,
                                     int lookMemberInfo, int applyMemberFriend,
                                     String notificationUserId) {
        requireAdminOrOwner(groupId, notificationUserId);
        PersistenceExceptions.runDatabase("set group information", () -> retryExecutor.execute(CFG, () -> {
        try (SqlSession session = MyBatisPlusFactory.openSession()) {
            GroupMapper mapper = session.getMapper(GroupMapper.class);
            GroupEntity entity = mapper.selectById(groupId);
            if (entity == null) throw new NotFoundException("group not found");
            if (groupName != null) entity.setGroupName(groupName);
            if (notification != null) entity.setNotification(notification);
            if (introduction != null) entity.setIntroduction(introduction);
            if (faceUrl != null) entity.setFaceUrl(faceUrl);
            if (needVerification >= 0) entity.setNeedVerification(needVerification);
            if (lookMemberInfo >= 0) entity.setLookMemberInfo(lookMemberInfo);
            if (applyMemberFriend >= 0) entity.setApplyMemberFriend(applyMemberFriend);
            if (notificationUserId != null) {
                entity.setNotificationUserId(notificationUserId);
                entity.setNotificationUpdateTime(System.currentTimeMillis());
            }
            entity.setUpdatedAt(System.currentTimeMillis());
            mapper.updateById(entity);
            session.commit();
        }
                    return null;
        }));
        sync.recordChange(groupId, "group_info", groupId, "update");
    }

    @Override
    public void addMember(String groupId, String userId) {
        long now = System.currentTimeMillis();
        PersistenceExceptions.runDatabase("add group member", () -> retryExecutor.execute(CFG, () -> {
        try (SqlSession session = MyBatisPlusFactory.openSession()) {
            GroupMapper groupMapper = session.getMapper(GroupMapper.class);
            GroupMemberMapper memberMapper = session.getMapper(GroupMemberMapper.class);
            if (isMemberInSession(memberMapper, groupId, userId)) return null;
            addMemberRecord(memberMapper, groupId, userId,
                    GroupMemberRole.MEMBER.getCode(), ApplySource.GROUP.getCode(), null, userId, now);
            GroupEntity entity = groupMapper.selectById(groupId);
            if (entity != null) {
                entity.setMemberCount(entity.getMemberCount() + 1);
                groupMapper.updateById(entity);
            }
            session.commit();
        }
                    return null;
        }));

        sync.recordChange(userId, "group", groupId, "insert");
        sync.recordChange(groupId, "member", userId, "insert");
    }

    @Override
    public void addMembers(String groupId, List<String> userIds) {
        for (String uid : userIds) addMember(groupId, uid);
    }

    @Override
    public void kickMember(String groupId, String operatorId, String targetUserId) {
        PersistenceExceptions.runDatabase("kick group member", () -> retryExecutor.execute(CFG, () -> {
        try (SqlSession session = MyBatisPlusFactory.openSession()) {
            GroupMapper groupMapper = session.getMapper(GroupMapper.class);
            GroupMemberMapper memberMapper = session.getMapper(GroupMemberMapper.class);
            GroupMemberEntity operator = getMemberInSession(memberMapper, groupId, operatorId);
            GroupMemberEntity target = getMemberInSession(memberMapper, groupId, targetUserId);
            if (operator == null) throw new ForbiddenException("not a group member");
            if (target == null) throw new NotFoundException("target group member not found");
            if (operator.getRoleLevel() < 100) throw new ForbiddenException("only group owner or admin can kick member");
            if (target.getRoleLevel() >= operator.getRoleLevel()) {
                throw new ForbiddenException("cannot kick member with same or higher role");
            }

            LambdaQueryWrapper<GroupMemberEntity> qw = new LambdaQueryWrapper<>();
            qw.eq(GroupMemberEntity::getGroupId, groupId)
                    .eq(GroupMemberEntity::getUserId, targetUserId);
            memberMapper.delete(qw);
            GroupEntity entity = groupMapper.selectById(groupId);
            if (entity != null) {
                entity.setMemberCount(Math.max(0, entity.getMemberCount() - 1));
                groupMapper.updateById(entity);
            }
            session.commit();
            log.info("Kicked: groupId={}, target={}, operator={}", groupId, targetUserId, operatorId);
        }
                    return null;
        }));

        sync.recordChange(targetUserId, "group", groupId, "delete");
        sync.recordChange(groupId, "member", targetUserId, "delete");
    }

    @Override
    public boolean quitGroup(String groupId, String userId) {
        boolean removed = PersistenceExceptions.runDatabase("quit group", () -> retryExecutor.execute(CFG, () -> {
        try (SqlSession session = MyBatisPlusFactory.openSession()) {
            GroupMapper groupMapper = session.getMapper(GroupMapper.class);
            GroupMemberMapper memberMapper = session.getMapper(GroupMemberMapper.class);
            GroupEntity group = groupMapper.selectById(groupId);
            if (group == null) return false;
            if (!canQuitGroup(group.getOwnerUserId(), userId)) {
                throw new ForbiddenException("group owner must transfer ownership or disband group before leaving");
            }
            LambdaQueryWrapper<GroupMemberEntity> qw = new LambdaQueryWrapper<>();
            qw.eq(GroupMemberEntity::getGroupId, groupId)
                    .eq(GroupMemberEntity::getUserId, userId);
            int deleted = memberMapper.delete(qw);
            if (deleted <= 0) {
                session.commit();
                return false;
            }
            group.setMemberCount(memberCountAfterRemove(group.getMemberCount()));
            group.setUpdatedAt(System.currentTimeMillis());
            groupMapper.updateById(group);
            session.commit();
            log.info("Quit: groupId={}, userId={}", groupId, userId);
            return true;
        }
        }));

        if (removed) {
            sync.recordChange(userId, "group", groupId, "delete");
            sync.recordChange(groupId, "member", userId, "delete");
        }
        return removed;
    }

    @Override
    public void transferOwner(String groupId, String oldOwnerId, String newOwnerId) {
        PersistenceExceptions.runDatabase("transfer group owner", () -> retryExecutor.execute(CFG, () -> {
        try (SqlSession session = MyBatisPlusFactory.openSession()) {
            GroupMapper groupMapper = session.getMapper(GroupMapper.class);
            GroupMemberMapper memberMapper = session.getMapper(GroupMemberMapper.class);

            if (oldOwnerId == null || oldOwnerId.isBlank()) {
                throw new ForbiddenException("operator is required");
            }
            if (newOwnerId == null || newOwnerId.isBlank()) {
                throw new ValidationException("targetUserId is required");
            }
            if (oldOwnerId.equals(newOwnerId)) {
                throw new ValidationException("cannot transfer group owner to self");
            }
            GroupEntity entity = groupMapper.selectById(groupId);
            if (entity == null) {
                throw new NotFoundException("group not found");
            }
            if (!oldOwnerId.equals(entity.getOwnerUserId())) {
                throw new ForbiddenException("only group owner can transfer ownership");
            }
            GroupMemberEntity oldOwner = getMemberInSession(memberMapper, groupId, oldOwnerId);
            if (oldOwner == null || oldOwner.getRoleLevel() != GROUP_ROLE_OWNER) {
                throw new ForbiddenException("operator is not group owner");
            }
            GroupMemberEntity newOwner = getMemberInSession(memberMapper, groupId, newOwnerId);
            if (newOwner == null) {
                throw new NotFoundException("target group member not found");
            }
            oldOwner.setRoleLevel(GroupMemberRole.MEMBER.getCode());
            memberMapper.updateById(oldOwner);
            newOwner.setRoleLevel(GROUP_ROLE_OWNER);
            memberMapper.updateById(newOwner);
            entity.setOwnerUserId(newOwnerId);
            entity.setUpdatedAt(System.currentTimeMillis());
            groupMapper.updateById(entity);
            session.commit();
        }
                    return null;
        }));

        sync.recordChange(groupId, "member", oldOwnerId, "update");
        sync.recordChange(groupId, "member", newOwnerId, "update");
        sync.recordChange(groupId, "group_info", groupId, "update");
    }

    @Override
    public void setMemberRole(String groupId, String operatorId, String targetUserId, int roleLevel) {
        PersistenceExceptions.runDatabase("set group member role", () -> retryExecutor.execute(CFG, () -> {
        try (SqlSession session = MyBatisPlusFactory.openSession()) {
            GroupMemberMapper mapper = session.getMapper(GroupMemberMapper.class);
            GroupMemberEntity operator = getMemberInSession(mapper, groupId, operatorId);
            if (operator == null || operator.getRoleLevel() != GROUP_ROLE_OWNER) {
                throw new ForbiddenException("only group owner can set member role");
            }
            GroupMemberEntity member = getMemberInSession(mapper, groupId, targetUserId);
            if (member == null) {
                throw new NotFoundException("target group member not found");
            }
            if (member.getRoleLevel() == GROUP_ROLE_OWNER) {
                throw new ForbiddenException("cannot change group owner role");
            }
            if (roleLevel != GroupMemberRole.MEMBER.getCode() && roleLevel != GROUP_ROLE_ADMIN) {
                throw new ValidationException("roleLevel must be MEMBER or ADMIN");
            }
            member.setRoleLevel(roleLevel);
            mapper.updateById(member);
            session.commit();
        }
                    return null;
        }));

        sync.recordChange(groupId, "member", targetUserId, "update");
    }

    @Override
    public void muteMember(String groupId, String targetUserId, long muteEndTime) {
        PersistenceExceptions.runDatabase("mute group member", () -> retryExecutor.execute(CFG, () -> {
        try (SqlSession session = MyBatisPlusFactory.openSession()) {
            GroupMemberMapper mapper = session.getMapper(GroupMemberMapper.class);
            GroupMemberEntity member = getMemberInSession(mapper, groupId, targetUserId);
            if (member != null) {
                member.setMuteEndTime(muteEndTime);
                mapper.updateById(member);
                session.commit();
            }
        }
                    return null;
        }));

        sync.recordChange(groupId, "member", targetUserId, "update");
    }

    @Override
    public void setMemberInfo(String groupId, String userId, String nickname) {
        String normalizedNickname = nickname != null ? nickname.trim() : "";
        if (normalizedNickname.isEmpty()) {
            throw new ValidationException("nickname is required");
        }
        PersistenceExceptions.runDatabase("set group member info", () -> retryExecutor.execute(CFG, () -> {
            try (SqlSession session = MyBatisPlusFactory.openSession()) {
                GroupMemberMapper mapper = session.getMapper(GroupMemberMapper.class);
                GroupMemberEntity member = getMemberInSession(mapper, groupId, userId);
                if (member == null) {
                    throw new ForbiddenException("not a group member");
                }
                member.setNickname(normalizedNickname);
                mapper.updateById(member);
                session.commit();
            }
            return null;
        }));

        sync.recordChange(groupId, "member", userId, "update");
    }

    private static final long FAR_FUTURE = 253402300799999L; // 9999-12-31 毫秒

    @Override
    public void muteGroupAll(String groupId, String operatorId, boolean mute) {
        // 校验操作者身份
        GroupMemberEntity operator = getMemberInSession(null, groupId, operatorId);
        if (operator == null || operator.getRoleLevel() < 100) {
            log.warn("Only admin can toggle mute-all: groupId={}, operator={}", groupId, operatorId);
            throw new ForbiddenException("only group owner or admin can mute all");
        }

        long muteEndTime = mute ? FAR_FUTURE : 0;
        PersistenceExceptions.runDatabase("mute group all", () -> retryExecutor.execute(CFG, () -> {
            try (SqlSession session = MyBatisPlusFactory.openSession()) {
                GroupMemberMapper mapper = session.getMapper(GroupMemberMapper.class);
                int updated = mapper.batchSetMuteEndTime(groupId, muteEndTime);
                session.commit();
                log.info("Group mute-all {}: groupId={}, operator={}, affected={}",
                        mute ? "enabled" : "disabled", groupId, operatorId, updated);
            }
            return null;
        }));
    }

    @Override
    public boolean isMemberMuted(String groupId, String userId) {
        GroupMemberEntity member = getMemberInSession(null, groupId, userId);
        if (member == null) return false;
        return member.getMuteEndTime() > 0 && member.getMuteEndTime() > System.currentTimeMillis();
    }

    @Override
    public GroupJoinResult joinGroup(String groupId, String userId, String reqMsg) {
        long now = System.currentTimeMillis();
        GroupJoinResult result = groupApplyPolicy.validateJoin(groupId, userId);
        if (result == GroupJoinResult.ALREADY_MEMBER || result == GroupJoinResult.ALREADY_PENDING) {
            return result;
        }
        return PersistenceExceptions.runDatabase("join group", () -> retryExecutor.execute(CFG, () -> {
            try (SqlSession session = MyBatisPlusFactory.openSession()) {
                GroupRequestMapper mapper = session.getMapper(GroupRequestMapper.class);
                GroupMapper groupMapper = session.getMapper(GroupMapper.class);
                GroupMemberMapper memberMapper = session.getMapper(GroupMemberMapper.class);
                if (result == GroupJoinResult.JOINED) {
                    int inserted = addMemberRecord(memberMapper, groupId, userId,
                            GroupMemberRole.MEMBER.getCode(), ApplySource.SEARCH.getCode(), null, userId, now);
                    if (inserted > 0) {
                        GroupEntity entity = groupMapper.selectById(groupId);
                        if (entity != null) {
                            entity.setMemberCount(entity.getMemberCount() + 1);
                            groupMapper.updateById(entity);
                        }
                    }
                    session.commit();
                    return GroupJoinResult.JOINED;
                }
                mapper.upsertPendingApply(groupId, userId, ApplyHandleResult.PENDING.getCode(),
                        reqMsg, ApplySource.SEARCH.getCode(), now);
                session.commit();
                log.info("Join request: groupId={}, userId={}", groupId, userId);
                return GroupJoinResult.APPLY_CREATED;
            }
        }));
    }

    @Override
    public GroupApplyHandleResult respondJoinRequest(String groupId, String userId, String operatorId,
                                                     String handleMsg, boolean agreed) {
        long now = System.currentTimeMillis();
        GroupApplyHandleResult result = PersistenceExceptions.runDatabase("respond join request", () -> retryExecutor.execute(CFG, () -> {
        try (SqlSession session = MyBatisPlusFactory.openSession()) {
            GroupRequestMapper mapper = session.getMapper(GroupRequestMapper.class);
            GroupMemberMapper memberMapper = session.getMapper(GroupMemberMapper.class);
            GroupMemberEntity operator = getMemberInSession(memberMapper, groupId, operatorId);
            if (operator == null || operator.getRoleLevel() < GROUP_ROLE_ADMIN) {
                throw new ForbiddenException("only group owner or admin can approve join request");
            }
            LambdaQueryWrapper<GroupRequestEntity> qw = new LambdaQueryWrapper<>();
            qw.eq(GroupRequestEntity::getGroupId, groupId)
                    .eq(GroupRequestEntity::getUserId, userId)
                    .eq(GroupRequestEntity::getHandleResult, ApplyHandleResult.PENDING.getCode());
            GroupRequestEntity req = mapper.selectOne(qw);
            if (req == null) return GroupApplyHandleResult.NOT_FOUND_OR_ALREADY_HANDLED;

            req.setHandleResult(agreed
                    ? ApplyHandleResult.AGREED.getCode()
                    : ApplyHandleResult.REJECTED.getCode());
            req.setHandledMsg(handleMsg);
            req.setHandlerUserId(operatorId);
            req.setHandledTime(now);
            mapper.updateById(req);

            if (agreed) {
                int inserted = addMemberRecord(memberMapper, groupId, userId,
                        GroupMemberRole.MEMBER.getCode(), ApplySource.SEARCH.getCode(), null, operatorId, now);
                if (inserted > 0) {
                    GroupMapper groupMapper = session.getMapper(GroupMapper.class);
                    GroupEntity entity = groupMapper.selectById(groupId);
                    if (entity != null) {
                        entity.setMemberCount(entity.getMemberCount() + 1);
                        groupMapper.updateById(entity);
                    }
                }
            }
            session.commit();
            return GroupApplyHandleResult.HANDLED;
        }
        }));
        if (agreed && result == GroupApplyHandleResult.HANDLED) {
            sync.recordChange(userId, "group", groupId, "insert");
            sync.recordChange(groupId, "member", userId, "insert");
        }
        return result;
    }

    @Override
    public List<GroupApply> getJoinRequests(String groupId, boolean onlyPending) {
        return PersistenceExceptions.runDatabase("get group join requests", () -> {
            try (SqlSession session = MyBatisPlusFactory.openSession()) {
                GroupRequestMapper mapper = session.getMapper(GroupRequestMapper.class);
                LambdaQueryWrapper<GroupRequestEntity> qw = new LambdaQueryWrapper<>();
                if (groupId != null) {
                    qw.eq(GroupRequestEntity::getGroupId, groupId);
                }
                if (onlyPending) {
                    qw.eq(GroupRequestEntity::getHandleResult, ApplyHandleResult.PENDING.getCode());
                }
                qw.orderByDesc(GroupRequestEntity::getCreatedAt);
                return mapper.selectList(qw).stream()
                        .map(this::toGroupApply)
                        .toList();
            }
        });
    }

    @Override
    public List<GroupApply> getManageableJoinRequests(String operatorId, boolean onlyPending) {
        return PersistenceExceptions.runDatabase("get manageable group join requests", () -> {
            try (SqlSession session = MyBatisPlusFactory.openSession()) {
                GroupMemberMapper memberMapper = session.getMapper(GroupMemberMapper.class);
                GroupRequestMapper requestMapper = session.getMapper(GroupRequestMapper.class);

                List<String> manageableGroupIds = memberMapper.selectList(
                        new LambdaQueryWrapper<GroupMemberEntity>()
                                .eq(GroupMemberEntity::getUserId, operatorId)
                                .ge(GroupMemberEntity::getRoleLevel, GROUP_ROLE_ADMIN)
                                .select(GroupMemberEntity::getGroupId)
                ).stream().map(GroupMemberEntity::getGroupId).toList();

                if (manageableGroupIds.isEmpty()) return List.of();

                LambdaQueryWrapper<GroupRequestEntity> qw = new LambdaQueryWrapper<>();
                qw.in(GroupRequestEntity::getGroupId, manageableGroupIds);
                if (onlyPending) {
                    qw.eq(GroupRequestEntity::getHandleResult, ApplyHandleResult.PENDING.getCode());
                }
                qw.orderByDesc(GroupRequestEntity::getCreatedAt);
                return requestMapper.selectList(qw).stream()
                        .map(this::toGroupApply)
                        .toList();
            }
        });
    }

    @Override
    public List<String> getManagerIds(String groupId) {
        return PersistenceExceptions.runDatabase("get group manager ids", () -> {
            try (SqlSession session = MyBatisPlusFactory.openSession()) {
                GroupMemberMapper mapper = session.getMapper(GroupMemberMapper.class);
                return mapper.selectAdmins(groupId).stream()
                        .map(GroupMemberEntity::getUserId)
                        .toList();
            }
        });
    }

    @Override
    public List<GroupMemberInformation> getMemberList(String groupId) {
        return PersistenceExceptions.runDatabase("get group member list", () -> {
            try (SqlSession session = MyBatisPlusFactory.openSession()) {
                GroupMemberMapper mapper = session.getMapper(GroupMemberMapper.class);
                LambdaQueryWrapper<GroupMemberEntity> qw = new LambdaQueryWrapper<>();
                qw.eq(GroupMemberEntity::getGroupId, groupId);
                return mapper.selectList(qw).stream()
                        .map(this::toGroupMemberInfo)
                        .toList();
            }
        });
    }

    @Override
    public Set<String> getMemberIds(String groupId) {
        return PersistenceExceptions.runDatabase("get group member ids", () -> {
            try (SqlSession session = MyBatisPlusFactory.openSession()) {
                GroupMemberMapper mapper = session.getMapper(GroupMemberMapper.class);
                return mapper.selectList(
                        new LambdaQueryWrapper<GroupMemberEntity>()
                                .eq(GroupMemberEntity::getGroupId, groupId)
                                .select(GroupMemberEntity::getUserId)
                ).stream().map(GroupMemberEntity::getUserId).collect(Collectors.toSet());
            }
        });
    }

    @Override
    public boolean isMember(String groupId, String userId) {
        return getMemberInSession(null, groupId, userId) != null;
    }

    @Override
    public String getRole(String groupId, String userId) {
        GroupMemberEntity member = getMemberInSession(null, groupId, userId);
        if (member == null) return null;
        if (member.getRoleLevel() == GROUP_ROLE_OWNER) return "owner";
        if (member.getRoleLevel() == GROUP_ROLE_ADMIN) return "admin";
        return "member";
    }

    @Override
    public Set<String> getJoinedGroups(String userId) {
        return PersistenceExceptions.runDatabase("get joined groups", () -> {
            try (SqlSession session = MyBatisPlusFactory.openSession()) {
                GroupMemberMapper mapper = session.getMapper(GroupMemberMapper.class);
                return mapper.selectList(
                        new LambdaQueryWrapper<GroupMemberEntity>()
                                .eq(GroupMemberEntity::getUserId, userId)
                                .select(GroupMemberEntity::getGroupId)
                ).stream().map(GroupMemberEntity::getGroupId).collect(Collectors.toSet());
            }
        });
    }

    @Override
    public List<GroupInformation> getJoinedGroupInformationList(String userId) {
        return PersistenceExceptions.runDatabase("get joined group information list", () -> {
            try (SqlSession session = MyBatisPlusFactory.openSession()) {
                GroupMemberMapper memberMapper = session.getMapper(GroupMemberMapper.class);
                GroupMapper groupMapper = session.getMapper(GroupMapper.class);

                List<String> groupIds = memberMapper.selectList(
                        new LambdaQueryWrapper<GroupMemberEntity>()
                                .eq(GroupMemberEntity::getUserId, userId)
                                .select(GroupMemberEntity::getGroupId)
                ).stream().map(GroupMemberEntity::getGroupId).toList();

                if (groupIds.isEmpty()) return List.of();

                return groupMapper.selectList(
                                new LambdaQueryWrapper<GroupEntity>()
                                        .in(GroupEntity::getGroupId, groupIds)
                                        .eq(GroupEntity::getStatus, GROUP_STATUS_NORMAL)
                                        .orderByDesc(GroupEntity::getUpdatedAt)
                        ).stream()
                        .map(this::toGroupInfo)
                        .collect(Collectors.toList());
            }
        });
    }

    @Override
    public GroupInformation getGroupInformation(String groupId) {
        return PersistenceExceptions.runDatabase("get group information", () -> {
            try (SqlSession session = MyBatisPlusFactory.openSession()) {
                GroupMapper mapper = session.getMapper(GroupMapper.class);
                GroupEntity entity = mapper.selectById(groupId);
                return entity != null ? toGroupInfo(entity) : null;
            }
        });
    }

    @Override
    public GroupStatus getGroupStatus(String groupId) {
        return PersistenceExceptions.runDatabase("get group status", () -> {
            try (SqlSession session = MyBatisPlusFactory.openSession()) {
                GroupMapper mapper = session.getMapper(GroupMapper.class);
                GroupEntity entity = mapper.selectById(groupId);
                return entity != null ? GroupStatus.fromCode(entity.getStatus()) : null;
            }
        });
    }

    @Override
    public GroupApplyPolicy.GroupSnapshot getGroup(String groupId) {
        return PersistenceExceptions.runDatabase("get group snapshot", () -> {
            try (SqlSession session = MyBatisPlusFactory.openSession()) {
                GroupMapper mapper = session.getMapper(GroupMapper.class);
                GroupEntity entity = mapper.selectById(groupId);
                if (entity == null) {
                    return null;
                }
                return new GroupApplyPolicy.GroupSnapshot(
                        GroupStatus.fromCode(entity.getStatus()),
                        GroupJoinVerification.fromCode(entity.getNeedVerification()));
            }
        });
    }

    // ========== 增量同步 ==========

    @Override
    public IncrementalSyncResult<String> getIncrementalGroups(String userId, long version) {
        return sync.getChangesAsIds(userId, "group", version);
    }

    @Override
    public IncrementalSyncResult<GroupMemberInformation> getIncrementalMembers(String groupId, long version) {
        return sync.getChanges(groupId, "member", version,
                uid -> {
                    return PersistenceExceptions.runDatabase("get incremental group member entity", () -> {
                        try (SqlSession session = MyBatisPlusFactory.openSession()) {
                            GroupMemberMapper mapper = session.getMapper(GroupMemberMapper.class);
                            GroupMemberEntity entity = getMemberInSession(mapper, groupId, uid);
                            return entity != null ? toGroupMemberInfo(entity) : null;
                        }
                    });
                },
                uid -> {
                    GroupMemberInformation gmi = new GroupMemberInformation();
                    gmi.setGroupId(groupId);
                    gmi.setUserId(uid);
                    gmi.setRoleLevel(GroupMemberRole.REMOVED);
                    return gmi;
                });
    }

    // ========== 内部方法 ==========

    private int addMemberRecord(GroupMemberMapper mapper, String groupId, String userId,
                                int roleLevel, int joinSource, String inviterId,
                                String operatorId, long now) {
        return mapper.upsertMember(groupId, userId, roleLevel, joinSource, inviterId, operatorId, now);
    }

    private GroupMemberEntity getMemberInSession(GroupMemberMapper mapper, String groupId, String userId) {
        if (mapper == null) {
            return PersistenceExceptions.runDatabase("get group member", () -> {
                try (SqlSession session = MyBatisPlusFactory.openSession()) {
                    GroupMemberMapper m = session.getMapper(GroupMemberMapper.class);
                    return doGetMember(m, groupId, userId);
                }
            });
        }
        return doGetMember(mapper, groupId, userId);
    }

    private GroupMemberEntity doGetMember(GroupMemberMapper mapper, String groupId, String userId) {
        LambdaQueryWrapper<GroupMemberEntity> qw = new LambdaQueryWrapper<>();
        qw.eq(GroupMemberEntity::getGroupId, groupId)
                .eq(GroupMemberEntity::getUserId, userId);
        return mapper.selectOne(qw);
    }

    private boolean isMemberInSession(GroupMemberMapper mapper, String groupId, String userId) {
        return getMemberInSession(mapper, groupId, userId) != null;
    }

    private GroupInformation toGroupInfo(GroupEntity entity) {
        GroupInformation gi = new GroupInformation(
                entity.getGroupId(), entity.getGroupName(), entity.getOwnerUserId());
        gi.setNotification(entity.getNotification());
        gi.setIntroduction(entity.getIntroduction());
        gi.setFaceUrl(entity.getFaceUrl());
        gi.setMemberCount(entity.getMemberCount());
        gi.setStatus(GroupStatus.fromCode(entity.getStatus()));
        gi.setGroupType(GroupType.fromCode(entity.getGroupType()));
        gi.setNeedVerification(GroupJoinVerification.fromCode(entity.getNeedVerification()));
        gi.setLookMemberInfo(GroupMemberInfoVisibility.fromCode(entity.getLookMemberInfo()));
        gi.setApplyMemberFriend(GroupMemberFriendPolicy.fromCode(entity.getApplyMemberFriend()));
        gi.setNotificationUserId(entity.getNotificationUserId());
        gi.setNotificationUpdateTime(entity.getNotificationUpdateTime());
        gi.setEx(entity.getEx());
        gi.setCreateTime(entity.getCreatedAt());
        gi.setUpdateTime(entity.getUpdatedAt());
        return gi;
    }

    private GroupMemberInformation toGroupMemberInfo(GroupMemberEntity entity) {
        GroupMemberInformation gmi = new GroupMemberInformation();
        gmi.setGroupId(entity.getGroupId());
        gmi.setUserId(entity.getUserId());
        gmi.setNickname(entity.getNickname());
        gmi.setFaceUrl(entity.getFaceUrl());
        gmi.setRoleLevel(GroupMemberRole.fromCode(entity.getRoleLevel()));
        gmi.setJoinSource(ApplySource.fromCode(entity.getJoinSource()));
        gmi.setInviterUserId(entity.getInviterUserId());
        gmi.setMuteEndTime(entity.getMuteEndTime());
        gmi.setEx(entity.getEx());
        gmi.setJoinedAt(entity.getJoinedAt());
        return gmi;
    }

    private GroupApply toGroupApply(GroupRequestEntity entity) {
        GroupApply ga = new GroupApply();
        ga.setGroupId(entity.getGroupId());
        ga.setUserId(entity.getUserId());
        hydrateGroupApplyDisplayFields(ga);
        ga.setReqMsg(entity.getReqMsg());
        ga.setHandledMsg(entity.getHandledMsg());
        ga.setHandlerUserId(entity.getHandlerUserId());
        ga.setHandleResult(ApplyHandleResult.fromCode(entity.getHandleResult()));
        ga.setJoinSource(ApplySource.fromCode(entity.getJoinSource()));
        ga.setInviterUserId(entity.getInviterUserId());
        ga.setCreateTime(entity.getCreatedAt());
        ga.setHandledTime(entity.getHandledTime());
        return ga;
    }

    private void hydrateGroupApplyDisplayFields(GroupApply apply) {
        try (SqlSession session = MyBatisPlusFactory.openSession()) {
            GroupMapper groupMapper = session.getMapper(GroupMapper.class);
            UserMapper userMapper = session.getMapper(UserMapper.class);
            GroupEntity group = groupMapper.selectById(apply.getGroupId());
            if (group != null) {
                apply.setGroupName(group.getGroupName());
            }
            UserEntity user = userMapper.selectById(apply.getUserId());
            if (user != null) {
                apply.setUserNickname(user.getNickname());
                apply.setUserFaceUrl(user.getFaceUrl());
            }
        }
    }

    private void requireAdminOrOwner(String groupId, String operatorId) {
        if (operatorId == null || operatorId.isBlank()) {
            throw new ForbiddenException("operator is required");
        }
        GroupMemberEntity operator = getMemberInSession(null, groupId, operatorId);
        if (operator == null || operator.getRoleLevel() < GROUP_ROLE_ADMIN) {
            throw new ForbiddenException("only group owner or admin can update group information");
        }
    }

    @Override
    public List<GroupInformation> searchGroups(String keyword, int limit) {
        return PersistenceExceptions.runDatabase("search groups", () -> {
            try (SqlSession session = MyBatisPlusFactory.openSession()) {
                GroupMapper mapper = session.getMapper(GroupMapper.class);
                var qw = new LambdaQueryWrapper<GroupEntity>()
                        .and(w -> w.like(GroupEntity::getGroupName, keyword)
                                .or()
                                .like(GroupEntity::getGroupId, keyword))
                        .eq(GroupEntity::getStatus, searchableGroupStatus())
                        .orderByDesc(GroupEntity::getCreatedAt)
                        .last("LIMIT " + limit);
                List<GroupEntity> entities = mapper.selectList(qw);
                return entities.stream().map(this::toGroupInfo).collect(Collectors.toList());
            }
        });
    }

    static int searchableGroupStatus() {
        return GROUP_STATUS_NORMAL;
    }

    static boolean canQuitGroup(String ownerUserId, String userId) {
        return ownerUserId == null || !ownerUserId.equals(userId);
    }

    static int memberCountAfterRemove(int currentMemberCount) {
        return Math.max(0, currentMemberCount - 1);
    }
}
