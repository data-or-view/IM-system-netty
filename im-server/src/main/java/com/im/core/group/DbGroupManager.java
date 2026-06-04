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
import com.im.common.exception.PersistenceExceptions;
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
public class DbGroupManager implements IGroupManager {

    private static final Logger log = LoggerFactory.getLogger(DbGroupManager.class);
    private static final RetryConfig CFG = RetryStrategies.DB_WRITE;

    private final RetryExecutor retryExecutor;
    private final DbIncrementalSync sync;

    public DbGroupManager(RetryExecutor retryExecutor) {
        this(retryExecutor, new DbIncrementalSync(retryExecutor));
    }

    public DbGroupManager(RetryExecutor retryExecutor, DbIncrementalSync sync) {
        this.retryExecutor = retryExecutor;
        this.sync = sync;
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
            entity.setStatus(1);
            entity.setGroupType(groupType);
            entity.setNeedVerification(needVerification);
            entity.setCreatedAt(now);
            entity.setUpdatedAt(now);
            groupMapper.insert(entity);

            addMemberRecord(memberMapper, groupId, ownerId, 200, 1, null, ownerId, now);

            if (members != null) {
                for (String m : members) {
                    if (!m.equals(ownerId)) {
                        addMemberRecord(memberMapper, groupId, m, 1, 2, null, ownerId, now);
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
    public void disbandGroup(String groupId, String operatorId) {
        Set<String> affectedMembers;
        affectedMembers = PersistenceExceptions.runDatabase("get affected group members before disband", () -> {
            try (SqlSession session = MyBatisPlusFactory.openSession()) {
                GroupMemberMapper memberMapper = session.getMapper(GroupMemberMapper.class);
                return memberMapper.selectList(
                        new LambdaQueryWrapper<GroupMemberEntity>()
                                .eq(GroupMemberEntity::getGroupId, groupId)
                                .select(GroupMemberEntity::getUserId)
                ).stream().map(GroupMemberEntity::getUserId).collect(Collectors.toSet());
            }
        });

        PersistenceExceptions.runDatabase("disband group", () -> retryExecutor.execute(CFG, () -> {
        try (SqlSession session = MyBatisPlusFactory.openSession()) {
            GroupMapper groupMapper = session.getMapper(GroupMapper.class);
            GroupMemberMapper memberMapper = session.getMapper(GroupMemberMapper.class);

            GroupEntity entity = groupMapper.selectById(groupId);
            if (entity == null) return null;
            if (!entity.getOwnerUserId().equals(operatorId)) {
                log.warn("Only owner can disband group: groupId={}, operator={}", groupId, operatorId);
                return null;
            }
            entity.setStatus(0);
            entity.setUpdatedAt(System.currentTimeMillis());
            groupMapper.updateById(entity);
            LambdaQueryWrapper<GroupMemberEntity> qw = new LambdaQueryWrapper<>();
            qw.eq(GroupMemberEntity::getGroupId, groupId);
            memberMapper.delete(qw);
            session.commit();
            log.info("Group disbanded: groupId={}", groupId);
        }
                    return null;
        }));

        for (String uid : affectedMembers) {
            sync.recordChange(uid, "group", groupId, "delete");
            sync.recordChange(groupId, "member", uid, "delete");
        }
    }

    @Override
    public void setGroupInformation(String groupId, String groupName, String notification,
                                     String introduction, String faceUrl, int needVerification,
                                     int lookMemberInfo, int applyMemberFriend,
                                     String notificationUserId) {
        PersistenceExceptions.runDatabase("set group information", () -> retryExecutor.execute(CFG, () -> {
        try (SqlSession session = MyBatisPlusFactory.openSession()) {
            GroupMapper mapper = session.getMapper(GroupMapper.class);
            GroupEntity entity = mapper.selectById(groupId);
            if (entity == null) return null;
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
    }

    @Override
    public void addMember(String groupId, String userId) {
        long now = System.currentTimeMillis();
        PersistenceExceptions.runDatabase("add group member", () -> retryExecutor.execute(CFG, () -> {
        try (SqlSession session = MyBatisPlusFactory.openSession()) {
            GroupMapper groupMapper = session.getMapper(GroupMapper.class);
            GroupMemberMapper memberMapper = session.getMapper(GroupMemberMapper.class);
            if (isMemberInSession(memberMapper, groupId, userId)) return null;
            addMemberRecord(memberMapper, groupId, userId, 1, 3, null, userId, now);
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
            if (operator == null || target == null) return null;
            if (operator.getRoleLevel() < 100 || target.getRoleLevel() >= operator.getRoleLevel()) return null;

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
    public void quitGroup(String groupId, String userId) {
        PersistenceExceptions.runDatabase("quit group", () -> retryExecutor.execute(CFG, () -> {
        try (SqlSession session = MyBatisPlusFactory.openSession()) {
            GroupMapper groupMapper = session.getMapper(GroupMapper.class);
            GroupMemberMapper memberMapper = session.getMapper(GroupMemberMapper.class);
            LambdaQueryWrapper<GroupMemberEntity> qw = new LambdaQueryWrapper<>();
            qw.eq(GroupMemberEntity::getGroupId, groupId)
                    .eq(GroupMemberEntity::getUserId, userId);
            memberMapper.delete(qw);
            GroupEntity entity = groupMapper.selectById(groupId);
            if (entity != null) {
                entity.setMemberCount(Math.max(0, entity.getMemberCount() - 1));
                groupMapper.updateById(entity);
            }
            session.commit();
            log.info("Quit: groupId={}, userId={}", groupId, userId);
        }
                    return null;
        }));

        sync.recordChange(userId, "group", groupId, "delete");
        sync.recordChange(groupId, "member", userId, "delete");
    }

    @Override
    public void transferOwner(String groupId, String oldOwnerId, String newOwnerId) {
        PersistenceExceptions.runDatabase("transfer group owner", () -> retryExecutor.execute(CFG, () -> {
        try (SqlSession session = MyBatisPlusFactory.openSession()) {
            GroupMapper groupMapper = session.getMapper(GroupMapper.class);
            GroupMemberMapper memberMapper = session.getMapper(GroupMemberMapper.class);

            GroupMemberEntity oldOwner = getMemberInSession(memberMapper, groupId, oldOwnerId);
            if (oldOwner != null) {
                oldOwner.setRoleLevel(100);
                memberMapper.updateById(oldOwner);
            }
            GroupMemberEntity newOwner = getMemberInSession(memberMapper, groupId, newOwnerId);
            if (newOwner != null) {
                newOwner.setRoleLevel(200);
                memberMapper.updateById(newOwner);
            }
            GroupEntity entity = groupMapper.selectById(groupId);
            if (entity != null) {
                entity.setOwnerUserId(newOwnerId);
                entity.setUpdatedAt(System.currentTimeMillis());
                groupMapper.updateById(entity);
            }
            session.commit();
        }
                    return null;
        }));

        sync.recordChange(groupId, "member", oldOwnerId, "update");
        sync.recordChange(groupId, "member", newOwnerId, "update");
    }

    @Override
    public void setMemberRole(String groupId, String operatorId, String targetUserId, int roleLevel) {
        PersistenceExceptions.runDatabase("set group member role", () -> retryExecutor.execute(CFG, () -> {
        try (SqlSession session = MyBatisPlusFactory.openSession()) {
            GroupMemberMapper mapper = session.getMapper(GroupMemberMapper.class);
            GroupMemberEntity member = getMemberInSession(mapper, groupId, targetUserId);
            if (member != null) {
                member.setRoleLevel(roleLevel);
                mapper.updateById(member);
                session.commit();
            }
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

    private static final long FAR_FUTURE = 253402300799999L; // 9999-12-31 毫秒

    @Override
    public void muteGroupAll(String groupId, String operatorId, boolean mute) {
        // 校验操作者身份
        GroupMemberEntity operator = getMemberInSession(null, groupId, operatorId);
        if (operator == null || operator.getRoleLevel() < 100) {
            log.warn("Only admin can toggle mute-all: groupId={}, operator={}", groupId, operatorId);
            return;
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
    public void joinGroup(String groupId, String userId, String reqMsg) {
        long now = System.currentTimeMillis();
        PersistenceExceptions.runDatabase("join group", () -> retryExecutor.execute(CFG, () -> {
        try (SqlSession session = MyBatisPlusFactory.openSession()) {
            GroupRequestMapper mapper = session.getMapper(GroupRequestMapper.class);
            GroupMapper groupMapper = session.getMapper(GroupMapper.class);
            GroupEntity group = groupMapper.selectById(groupId);
            if (group == null) return null;

            LambdaQueryWrapper<GroupRequestEntity> qw = new LambdaQueryWrapper<>();
            qw.eq(GroupRequestEntity::getGroupId, groupId)
                    .eq(GroupRequestEntity::getUserId, userId)
                    .eq(GroupRequestEntity::getHandleResult, 0);
            if (mapper.selectCount(qw) > 0) return null;

            if (group.getNeedVerification() == 0) {
                addMember(groupId, userId);
                return null;
            }

            GroupRequestEntity req = new GroupRequestEntity();
            req.setGroupId(groupId);
            req.setUserId(userId);
            req.setReqMsg(reqMsg);
            req.setHandleResult(0);
            req.setCreatedAt(now);
            mapper.insert(req);
            session.commit();
            log.info("Join request: groupId={}, userId={}", groupId, userId);
        }
                    return null;
        }));
    }

    @Override
    public void respondJoinRequest(String groupId, String userId, String operatorId,
                                    String handleMsg, boolean agreed) {
        long now = System.currentTimeMillis();
        PersistenceExceptions.runDatabase("respond join request", () -> retryExecutor.execute(CFG, () -> {
        try (SqlSession session = MyBatisPlusFactory.openSession()) {
            GroupRequestMapper mapper = session.getMapper(GroupRequestMapper.class);
            LambdaQueryWrapper<GroupRequestEntity> qw = new LambdaQueryWrapper<>();
            qw.eq(GroupRequestEntity::getGroupId, groupId)
                    .eq(GroupRequestEntity::getUserId, userId)
                    .eq(GroupRequestEntity::getHandleResult, 0);
            GroupRequestEntity req = mapper.selectOne(qw);
            if (req == null) return null;

            req.setHandleResult(agreed ? 1 : 2);
            req.setHandledMsg(handleMsg);
            req.setHandlerUserId(operatorId);
            req.setHandledTime(now);
            mapper.updateById(req);

            if (agreed) {
                addMember(groupId, userId);
            }
            session.commit();
        }
                    return null;
        }));
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
                    qw.eq(GroupRequestEntity::getHandleResult, 0);
                }
                qw.orderByDesc(GroupRequestEntity::getCreatedAt);
                return mapper.selectList(qw).stream()
                        .map(this::toGroupApply)
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
        return switch (member.getRoleLevel()) {
            case 200 -> "owner";
            case 100 -> "admin";
            default -> "member";
        };
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
    public GroupInformation getGroupInformation(String groupId) {
        return PersistenceExceptions.runDatabase("get group information", () -> {
            try (SqlSession session = MyBatisPlusFactory.openSession()) {
                GroupMapper mapper = session.getMapper(GroupMapper.class);
                GroupEntity entity = mapper.selectById(groupId);
                return entity != null ? toGroupInfo(entity) : null;
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
                    gmi.setRoleLevel(-1);
                    return gmi;
                });
    }

    // ========== 内部方法 ==========

    private void addMemberRecord(GroupMemberMapper mapper, String groupId, String userId,
                                  int roleLevel, int joinSource, String inviterId,
                                  String operatorId, long now) {
        GroupMemberEntity me = new GroupMemberEntity();
        me.setGroupId(groupId);
        me.setUserId(userId);
        me.setRoleLevel(roleLevel);
        me.setJoinSource(joinSource);
        me.setInviterUserId(inviterId);
        me.setOperatorUserId(operatorId);
        me.setJoinedAt(now);
        mapper.insert(me);
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
        gi.setStatus(entity.getStatus());
        gi.setGroupType(entity.getGroupType());
        gi.setNeedVerification(entity.getNeedVerification());
        gi.setLookMemberInfo(entity.getLookMemberInfo());
        gi.setApplyMemberFriend(entity.getApplyMemberFriend());
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
        gmi.setRoleLevel(entity.getRoleLevel());
        gmi.setJoinSource(entity.getJoinSource());
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
        ga.setReqMsg(entity.getReqMsg());
        ga.setHandledMsg(entity.getHandledMsg());
        ga.setHandlerUserId(entity.getHandlerUserId());
        ga.setHandleResult(entity.getHandleResult());
        ga.setJoinSource(entity.getJoinSource());
        ga.setInviterUserId(entity.getInviterUserId());
        ga.setCreateTime(entity.getCreatedAt());
        ga.setHandledTime(entity.getHandledTime());
        return ga;
    }

    @Override
    public List<GroupInformation> searchGroups(String keyword, int limit) {
        return PersistenceExceptions.runDatabase("search groups", () -> {
            try (SqlSession session = MyBatisPlusFactory.openSession()) {
                GroupMapper mapper = session.getMapper(GroupMapper.class);
                var qw = new LambdaQueryWrapper<GroupEntity>()
                        .like(GroupEntity::getGroupName, keyword)
                        .eq(GroupEntity::getStatus, 0)
                        .orderByDesc(GroupEntity::getCreatedAt)
                        .last("LIMIT " + limit);
                List<GroupEntity> entities = mapper.selectList(qw);
                return entities.stream().map(this::toGroupInfo).collect(Collectors.toList());
            }
        });
    }
}
