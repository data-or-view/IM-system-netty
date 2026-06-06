package com.im.core.friend;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.im.api.ApplyHandleResult;
import com.im.api.ApplySource;
import com.im.api.FriendApply;
import com.im.api.FriendInformation;
import com.im.api.IFriendManager;
import com.im.api.IncrementalSyncResult;
import com.im.core.db.MyBatisPlusFactory;
import com.im.core.db.entity.BlacklistEntity;
import com.im.core.db.entity.FriendEntity;
import com.im.core.db.entity.FriendRequestEntity;
import com.im.core.db.mapper.BlacklistMapper;
import com.im.core.db.mapper.FriendMapper;
import com.im.core.db.mapper.FriendRequestMapper;
import com.im.core.sync.DbIncrementalSync;
import com.im.common.exception.PersistenceExceptions;
import com.im.common.retry.RetryConfig;
import com.im.common.retry.RetryExecutor;
import com.im.common.retry.RetryStrategies;
import org.apache.ibatis.session.SqlSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 数据库好友管理器。
 *
 * <p>基于 MyBatis-Plus，所有好友关系数据读写 {@code im_friends}、
 * {@code im_friend_requests}、{@code im_blacklist} 表。</p>
 */
public class DbFriendManager implements IFriendManager, FriendApplyPolicy.Gateway {

    private static final Logger log = LoggerFactory.getLogger(DbFriendManager.class);
    private static final RetryConfig CFG = RetryStrategies.DB_WRITE;

    private final RetryExecutor retryExecutor;
    private final DbIncrementalSync sync;
    private final FriendApplyPolicy applyPolicy;

    public DbFriendManager(RetryExecutor retryExecutor) {
        this(retryExecutor, new DbIncrementalSync(retryExecutor));
    }

    public DbFriendManager(RetryExecutor retryExecutor, DbIncrementalSync sync) {
        this.retryExecutor = retryExecutor;
        this.sync = sync;
        this.applyPolicy = new FriendApplyPolicy(this);
    }

    @Override
    public void applyAddFriend(String fromUserId, String toUserId, String reqMsg) {
        PersistenceExceptions.runDatabase("apply add friend", () -> retryExecutor.execute(CFG, () -> {
            long now = System.currentTimeMillis();
            try (SqlSession session = MyBatisPlusFactory.openSession()) {
                FriendRequestMapper mapper = session.getMapper(FriendRequestMapper.class);
                if (applyPolicy.validateApply(fromUserId, toUserId) == FriendApplyPolicy.Decision.ALREADY_PENDING) {
                    log.warn("Duplicate friend apply: {} -> {} already pending", fromUserId, toUserId);
                    return null;
                }
                mapper.upsertPendingApply(fromUserId, toUserId, ApplyHandleResult.PENDING.getCode(), reqMsg, now);
                session.commit();
                log.info("Friend apply: {} -> {} (req={})", fromUserId, toUserId, reqMsg);
            }
            return null;
        }));
    }

    @Override
    public void respondFriendApply(String userId, String fromUserId, String handleMsg, boolean agreed) {
        PersistenceExceptions.runDatabase("respond friend apply", () -> retryExecutor.execute(CFG, () -> {
            long now = System.currentTimeMillis();
            try (SqlSession session = MyBatisPlusFactory.openSession()) {
                FriendRequestMapper reqMapper = session.getMapper(FriendRequestMapper.class);
                FriendMapper friendMapper = session.getMapper(FriendMapper.class);
                LambdaQueryWrapper<FriendRequestEntity> qw = new LambdaQueryWrapper<>();
                qw.eq(FriendRequestEntity::getFromUserId, fromUserId)
                        .eq(FriendRequestEntity::getToUserId, userId)
                        .eq(FriendRequestEntity::getHandleResult, ApplyHandleResult.PENDING.getCode());
                FriendRequestEntity req = reqMapper.selectOne(qw);
                if (req == null) {
                    log.warn("No pending friend apply from {} to {}", fromUserId, userId);
                    return null;
                }
                req.setHandleResult(agreed
                        ? ApplyHandleResult.AGREED.getCode()
                        : ApplyHandleResult.REJECTED.getCode());
                req.setHandlerUserId(userId);
                req.setHandleMsg(handleMsg);
                req.setHandleTime(now);
                reqMapper.updateById(req);
                if (agreed) {
                    int addSource = ApplySource.SEARCH.getCode();
                    friendMapper.upsertFriend(userId, fromUserId, addSource, userId, now);
                    friendMapper.upsertFriend(fromUserId, userId, addSource, userId, now);
                }
                session.commit();
                log.info("Friend apply response: {} -> {}, agreed={}", fromUserId, userId, agreed);
            }
            return null;
        }));

        // 记录增量同步（独立 session）
        if (agreed) {
            sync.recordChange(userId, "friend", fromUserId, "insert");
            sync.recordChange(fromUserId, "friend", userId, "insert");
        }
    }

    @Override
    public List<FriendApply> getFriendApplyList(String userId, boolean onlyPending) {
        return PersistenceExceptions.runDatabase("get friend apply list", () -> {
            try (SqlSession session = MyBatisPlusFactory.openSession()) {
                FriendRequestMapper mapper = session.getMapper(FriendRequestMapper.class);
                LambdaQueryWrapper<FriendRequestEntity> qw = new LambdaQueryWrapper<>();
                qw.eq(FriendRequestEntity::getToUserId, userId);
                if (onlyPending) qw.eq(FriendRequestEntity::getHandleResult, ApplyHandleResult.PENDING.getCode());
                qw.orderByDesc(FriendRequestEntity::getCreatedAt);
                return mapper.selectList(qw).stream().map(this::toFriendApply).toList();
            }
        });
    }

    @Override
    public void deleteFriend(String ownerUserId, String friendUserId) {
        PersistenceExceptions.runDatabase("delete friend", () -> retryExecutor.execute(CFG, () -> {
            try (SqlSession session = MyBatisPlusFactory.openSession()) {
                FriendMapper mapper = session.getMapper(FriendMapper.class);
                LambdaQueryWrapper<FriendEntity> qw = new LambdaQueryWrapper<>();
                qw.eq(FriendEntity::getOwnerUserId, ownerUserId).eq(FriendEntity::getFriendUserId, friendUserId);
                mapper.delete(qw);
                qw = new LambdaQueryWrapper<>();
                qw.eq(FriendEntity::getOwnerUserId, friendUserId).eq(FriendEntity::getFriendUserId, ownerUserId);
                mapper.delete(qw);
                session.commit();
                log.info("Friend deleted: owner={}, friend={}", ownerUserId, friendUserId);
            }
            return null;
        }));

        sync.recordChange(ownerUserId, "friend", friendUserId, "delete");
        sync.recordChange(friendUserId, "friend", ownerUserId, "delete");
    }

    @Override
    public List<FriendInformation> getFriendList(String userId) {
        return PersistenceExceptions.runDatabase("get friend list", () -> {
            try (SqlSession session = MyBatisPlusFactory.openSession()) {
                FriendMapper mapper = session.getMapper(FriendMapper.class);
                return mapper.selectList(new LambdaQueryWrapper<FriendEntity>()
                                .eq(FriendEntity::getOwnerUserId, userId)).stream()
                        .map(this::toFriendInformation).toList();
            }
        });
    }

    @Override
    public boolean isFriend(String userIdA, String userIdB) {
        return PersistenceExceptions.runDatabase("check friend relation", () -> {
            try (SqlSession session = MyBatisPlusFactory.openSession()) {
                FriendMapper mapper = session.getMapper(FriendMapper.class);
                return mapper.selectCount(new LambdaQueryWrapper<FriendEntity>()
                        .eq(FriendEntity::getOwnerUserId, userIdA)
                        .eq(FriendEntity::getFriendUserId, userIdB)) > 0;
            }
        });
    }

    @Override
    public void setFriendRemark(String ownerUserId, String friendUserId, String remark) {
        PersistenceExceptions.runDatabase("set friend remark", () -> retryExecutor.execute(CFG, () -> {
            try (SqlSession session = MyBatisPlusFactory.openSession()) {
                FriendMapper mapper = session.getMapper(FriendMapper.class);
                FriendEntity entity = mapper.selectOne(new LambdaQueryWrapper<FriendEntity>()
                        .eq(FriendEntity::getOwnerUserId, ownerUserId)
                        .eq(FriendEntity::getFriendUserId, friendUserId));
                if (entity != null) {
                    entity.setRemark(remark);
                    mapper.updateById(entity);
                    session.commit();
                }
            }
            return null;
        }));

        sync.recordChange(ownerUserId, "friend", friendUserId, "update");
    }

    @Override
    public void setFriendPinned(String ownerUserId, String friendUserId, boolean pinned) {
        PersistenceExceptions.runDatabase("set friend pinned", () -> retryExecutor.execute(CFG, () -> {
            try (SqlSession session = MyBatisPlusFactory.openSession()) {
                FriendMapper mapper = session.getMapper(FriendMapper.class);
                FriendEntity entity = mapper.selectOne(new LambdaQueryWrapper<FriendEntity>()
                        .eq(FriendEntity::getOwnerUserId, ownerUserId)
                        .eq(FriendEntity::getFriendUserId, friendUserId));
                if (entity != null) {
                    entity.setIsPinned(pinned ? 1 : 0);
                    mapper.updateById(entity);
                    session.commit();
                }
            }
            return null;
        }));

        sync.recordChange(ownerUserId, "friend", friendUserId, "update");
    }

    @Override
    public boolean isBlocked(String fromUserId, String toUserId) {
        return PersistenceExceptions.runDatabase("check blacklist relation", () -> {
            try (SqlSession session = MyBatisPlusFactory.openSession()) {
                BlacklistMapper mapper = session.getMapper(BlacklistMapper.class);
                return mapper.selectCount(new LambdaQueryWrapper<BlacklistEntity>()
                        .eq(BlacklistEntity::getOwnerUserId, toUserId)
                        .eq(BlacklistEntity::getBlockUserId, fromUserId)) > 0;
            }
        });
    }

    @Override
    public void addBlack(String ownerUserId, String blockedUserId) {
        PersistenceExceptions.runDatabase("add blacklist", () -> retryExecutor.execute(CFG, () -> {
            try (SqlSession session = MyBatisPlusFactory.openSession()) {
                BlacklistMapper mapper = session.getMapper(BlacklistMapper.class);
                if (mapper.selectCount(new LambdaQueryWrapper<BlacklistEntity>()
                        .eq(BlacklistEntity::getOwnerUserId, ownerUserId)
                        .eq(BlacklistEntity::getBlockUserId, blockedUserId)) > 0) return null;
                BlacklistEntity entity = new BlacklistEntity();
                entity.setOwnerUserId(ownerUserId);
                entity.setBlockUserId(blockedUserId);
                entity.setAddSource(ApplySource.SEARCH.getCode());
                entity.setOperatorUserId(ownerUserId);
                entity.setCreatedAt(System.currentTimeMillis());
                mapper.insert(entity);
                session.commit();
                log.info("Blacklist add: {} blocks {}", ownerUserId, blockedUserId);
            }
            return null;
        }));

        sync.recordChange(ownerUserId, "black", blockedUserId, "insert");
    }

    @Override
    public void removeBlack(String ownerUserId, String blockedUserId) {
        PersistenceExceptions.runDatabase("remove blacklist", () -> retryExecutor.execute(CFG, () -> {
            try (SqlSession session = MyBatisPlusFactory.openSession()) {
                BlacklistMapper mapper = session.getMapper(BlacklistMapper.class);
                mapper.delete(new LambdaQueryWrapper<BlacklistEntity>()
                        .eq(BlacklistEntity::getOwnerUserId, ownerUserId)
                        .eq(BlacklistEntity::getBlockUserId, blockedUserId));
                session.commit();
            }
            return null;
        }));

        sync.recordChange(ownerUserId, "black", blockedUserId, "delete");
    }

    @Override
    public List<String> getBlackList(String userId) {
        return PersistenceExceptions.runDatabase("get blacklist", () -> {
            try (SqlSession session = MyBatisPlusFactory.openSession()) {
                BlacklistMapper mapper = session.getMapper(BlacklistMapper.class);
                return mapper.selectList(new LambdaQueryWrapper<BlacklistEntity>()
                                .eq(BlacklistEntity::getOwnerUserId, userId)).stream()
                        .map(BlacklistEntity::getBlockUserId).toList();
            }
        });
    }

    // ========== 增量同步 ==========

    @Override
    public IncrementalSyncResult<FriendInformation> getIncrementalFriends(String userId, long version) {
        return sync.getChanges(userId, "friend", version,
                fid -> {
                    // 从数据库查询当前好友状态
                    return PersistenceExceptions.runDatabase("get incremental friend entity", () -> {
                        try (SqlSession session = MyBatisPlusFactory.openSession()) {
                            FriendMapper mapper = session.getMapper(FriendMapper.class);
                            FriendEntity entity = mapper.selectOne(new LambdaQueryWrapper<FriendEntity>()
                                    .eq(FriendEntity::getOwnerUserId, userId)
                                    .eq(FriendEntity::getFriendUserId, fid));
                            return entity != null ? toFriendInformation(entity) : null;
                        }
                    });
                },
                fid -> {
                    FriendInformation fi = new FriendInformation();
                    fi.setOwnerUserId(userId);
                    fi.setFriendUserId(fid);
                    fi.setDeleted(true);
                    return fi;
                });
    }

    @Override
    public IncrementalSyncResult<String> getIncrementalBlacks(String userId, long version) {
        return sync.getChangesAsIds(userId, "black", version);
    }

    // ── 好友申请查询 ──

    @Override
    public List<FriendApply> getSentFriendApplyList(String userId) {
        return PersistenceExceptions.runDatabase("get sent friend apply list", () -> {
            try (SqlSession session = MyBatisPlusFactory.openSession()) {
                FriendRequestMapper mapper = session.getMapper(FriendRequestMapper.class);
                LambdaQueryWrapper<FriendRequestEntity> qw = new LambdaQueryWrapper<>();
                qw.eq(FriendRequestEntity::getFromUserId, userId);
                qw.orderByDesc(FriendRequestEntity::getCreatedAt);
                return mapper.selectList(qw).stream().map(this::toFriendApply).toList();
            }
        });
    }

    @Override
    public FriendApply getFriendApplyDetail(String fromUserId, String toUserId) {
        return PersistenceExceptions.runDatabase("get friend apply detail", () -> {
            try (SqlSession session = MyBatisPlusFactory.openSession()) {
                FriendRequestMapper mapper = session.getMapper(FriendRequestMapper.class);
                LambdaQueryWrapper<FriendRequestEntity> qw = new LambdaQueryWrapper<>();
                qw.eq(FriendRequestEntity::getFromUserId, fromUserId)
                        .eq(FriendRequestEntity::getToUserId, toUserId);
                FriendRequestEntity entity = mapper.selectOne(qw);
                return entity != null ? toFriendApply(entity) : null;
            }
        });
    }

    @Override
    public int getUnhandledApplyCount(String userId) {
        return PersistenceExceptions.runDatabase("get unhandled friend apply count", () -> {
            try (SqlSession session = MyBatisPlusFactory.openSession()) {
                FriendRequestMapper mapper = session.getMapper(FriendRequestMapper.class);
                Long count = mapper.selectCount(new LambdaQueryWrapper<FriendRequestEntity>()
                        .eq(FriendRequestEntity::getToUserId, userId)
                        .eq(FriendRequestEntity::getHandleResult, ApplyHandleResult.PENDING.getCode()));
                return count != null ? count.intValue() : 0;
            }
        });
    }

    // ── 转换 ──

    private FriendApply toFriendApply(FriendRequestEntity entity) {
        FriendApply apply = new FriendApply();
        apply.setFromUserId(entity.getFromUserId());
        apply.setToUserId(entity.getToUserId());
        apply.setReqMsg(entity.getReqMsg());
        apply.setHandlerUserId(entity.getHandlerUserId());
        apply.setHandleMsg(entity.getHandleMsg());
        apply.setHandleResult(ApplyHandleResult.fromCode(entity.getHandleResult()));
        apply.setCreateTime(entity.getCreatedAt());
        apply.setHandleTime(entity.getHandleTime());
        return apply;
    }

    private FriendInformation toFriendInformation(FriendEntity entity) {
        FriendInformation fi = new FriendInformation();
        fi.setOwnerUserId(entity.getOwnerUserId());
        fi.setFriendUserId(entity.getFriendUserId());
        fi.setRemark(entity.getRemark());
        fi.setAddSource(ApplySource.fromCode(entity.getAddSource()));
        fi.setEx(entity.getEx());
        fi.setCreateTime(entity.getCreatedAt());
        fi.setPinned(entity.getIsPinned() == 1);
        return fi;
    }
}
