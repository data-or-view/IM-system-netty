package com.im.core.friend;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.im.api.FriendApply;
import com.im.api.FriendInformation;
import com.im.api.IFriendManager;
import com.im.core.db.MyBatisPlusFactory;
import com.im.core.db.entity.BlacklistEntity;
import com.im.core.db.entity.FriendEntity;
import com.im.core.db.entity.FriendRequestEntity;
import com.im.core.db.mapper.BlacklistMapper;
import com.im.core.db.mapper.FriendMapper;
import com.im.core.db.mapper.FriendRequestMapper;
import com.im.core.retry.RetryConfig;
import com.im.core.retry.RetryExecutor;
import com.im.core.retry.RetryStrategies;
import org.apache.ibatis.session.SqlSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 数据库好友管理器。
 *
 * <p>基于 MyBatis-Plus，所有好友关系数据读写 {@code im_friends}、
 * {@code im_friend_requests}、{@code im_blacklist} 表。</p>
 */
public class DbFriendManager implements IFriendManager {

    private static final Logger log = LoggerFactory.getLogger(DbFriendManager.class);
    private static final RetryConfig CFG = RetryStrategies.DB_WRITE;

    private final RetryExecutor retryExecutor;

    public DbFriendManager(RetryExecutor retryExecutor) {
        this.retryExecutor = retryExecutor;
    }

    @Override
    public void applyAddFriend(String fromUserId, String toUserId, String reqMsg) {
        retryExecutor.execute(CFG, () -> {
            long now = System.currentTimeMillis();
            try (SqlSession session = MyBatisPlusFactory.openSession()) {
                FriendRequestMapper mapper = session.getMapper(FriendRequestMapper.class);
                LambdaQueryWrapper<FriendRequestEntity> qw = new LambdaQueryWrapper<>();
                qw.eq(FriendRequestEntity::getFromUserId, fromUserId)
                        .eq(FriendRequestEntity::getToUserId, toUserId)
                        .eq(FriendRequestEntity::getHandleResult, 0);
                if (mapper.selectCount(qw) > 0) {
                    log.warn("Duplicate friend apply: {} -> {} already pending", fromUserId, toUserId);
                    return null;
                }
                FriendRequestEntity entity = new FriendRequestEntity();
                entity.setFromUserId(fromUserId);
                entity.setToUserId(toUserId);
                entity.setReqMsg(reqMsg);
                entity.setHandleResult(0);
                entity.setCreatedAt(now);
                mapper.insert(entity);
                session.commit();
                log.info("Friend apply: {} -> {} (req={})", fromUserId, toUserId, reqMsg);
            }
            return null;
        });
    }

    @Override
    public void respondFriendApply(String userId, String fromUserId, String handleMsg, boolean agreed) {
        retryExecutor.execute(CFG, () -> {
            long now = System.currentTimeMillis();
            try (SqlSession session = MyBatisPlusFactory.openSession()) {
                FriendRequestMapper reqMapper = session.getMapper(FriendRequestMapper.class);
                FriendMapper friendMapper = session.getMapper(FriendMapper.class);
                LambdaQueryWrapper<FriendRequestEntity> qw = new LambdaQueryWrapper<>();
                qw.eq(FriendRequestEntity::getFromUserId, fromUserId)
                        .eq(FriendRequestEntity::getToUserId, userId)
                        .eq(FriendRequestEntity::getHandleResult, 0);
                FriendRequestEntity req = reqMapper.selectOne(qw);
                if (req == null) {
                    log.warn("No pending friend apply from {} to {}", fromUserId, userId);
                    return null;
                }
                req.setHandleResult(agreed ? 1 : 2);
                req.setHandlerUserId(userId);
                req.setHandleMsg(handleMsg);
                req.setHandleTime(now);
                reqMapper.updateById(req);
                if (agreed) {
                    FriendEntity friendA = new FriendEntity();
                    friendA.setOwnerUserId(userId);
                    friendA.setFriendUserId(fromUserId);
                    friendA.setAddSource(1);
                    friendA.setOperatorUserId(userId);
                    friendA.setCreatedAt(now);
                    friendMapper.insert(friendA);
                    FriendEntity friendB = new FriendEntity();
                    friendB.setOwnerUserId(fromUserId);
                    friendB.setFriendUserId(userId);
                    friendB.setAddSource(1);
                    friendB.setOperatorUserId(userId);
                    friendB.setCreatedAt(now);
                    friendMapper.insert(friendB);
                }
                session.commit();
                log.info("Friend apply response: {} -> {}, agreed={}", fromUserId, userId, agreed);
            }
            return null;
        });
    }

    @Override
    public List<FriendApply> getFriendApplyList(String userId, boolean onlyPending) {
        try (SqlSession session = MyBatisPlusFactory.openSession()) {
            FriendRequestMapper mapper = session.getMapper(FriendRequestMapper.class);
            LambdaQueryWrapper<FriendRequestEntity> qw = new LambdaQueryWrapper<>();
            qw.eq(FriendRequestEntity::getToUserId, userId);
            if (onlyPending) qw.eq(FriendRequestEntity::getHandleResult, 0);
            qw.orderByDesc(FriendRequestEntity::getCreatedAt);
            return mapper.selectList(qw).stream().map(this::toFriendApply).toList();
        }
    }

    @Override
    public void deleteFriend(String ownerUserId, String friendUserId) {
        retryExecutor.execute(CFG, () -> {
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
        });
    }

    @Override
    public List<FriendInformation> getFriendList(String userId) {
        try (SqlSession session = MyBatisPlusFactory.openSession()) {
            FriendMapper mapper = session.getMapper(FriendMapper.class);
            return mapper.selectList(new LambdaQueryWrapper<FriendEntity>()
                    .eq(FriendEntity::getOwnerUserId, userId)).stream()
                    .map(this::toFriendInformation).toList();
        }
    }

    @Override
    public boolean isFriend(String userIdA, String userIdB) {
        try (SqlSession session = MyBatisPlusFactory.openSession()) {
            FriendMapper mapper = session.getMapper(FriendMapper.class);
            return mapper.selectCount(new LambdaQueryWrapper<FriendEntity>()
                    .eq(FriendEntity::getOwnerUserId, userIdA)
                    .eq(FriendEntity::getFriendUserId, userIdB)) > 0;
        }
    }

    @Override
    public void setFriendRemark(String ownerUserId, String friendUserId, String remark) {
        retryExecutor.execute(CFG, () -> {
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
        });
    }

    @Override
    public void setFriendPinned(String ownerUserId, String friendUserId, boolean pinned) {
        retryExecutor.execute(CFG, () -> {
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
        });
    }

    @Override
    public boolean isBlocked(String fromUserId, String toUserId) {
        try (SqlSession session = MyBatisPlusFactory.openSession()) {
            BlacklistMapper mapper = session.getMapper(BlacklistMapper.class);
            return mapper.selectCount(new LambdaQueryWrapper<BlacklistEntity>()
                    .eq(BlacklistEntity::getOwnerUserId, toUserId)
                    .eq(BlacklistEntity::getBlockUserId, fromUserId)) > 0;
        }
    }

    @Override
    public void addBlack(String ownerUserId, String blockedUserId) {
        retryExecutor.execute(CFG, () -> {
            try (SqlSession session = MyBatisPlusFactory.openSession()) {
                BlacklistMapper mapper = session.getMapper(BlacklistMapper.class);
                if (mapper.selectCount(new LambdaQueryWrapper<BlacklistEntity>()
                        .eq(BlacklistEntity::getOwnerUserId, ownerUserId)
                        .eq(BlacklistEntity::getBlockUserId, blockedUserId)) > 0) return null;
                BlacklistEntity entity = new BlacklistEntity();
                entity.setOwnerUserId(ownerUserId);
                entity.setBlockUserId(blockedUserId);
                entity.setAddSource(1);
                entity.setOperatorUserId(ownerUserId);
                entity.setCreatedAt(System.currentTimeMillis());
                mapper.insert(entity);
                session.commit();
                log.info("Blacklist add: {} blocks {}", ownerUserId, blockedUserId);
            }
            return null;
        });
    }

    @Override
    public void removeBlack(String ownerUserId, String blockedUserId) {
        retryExecutor.execute(CFG, () -> {
            try (SqlSession session = MyBatisPlusFactory.openSession()) {
                BlacklistMapper mapper = session.getMapper(BlacklistMapper.class);
                mapper.delete(new LambdaQueryWrapper<BlacklistEntity>()
                        .eq(BlacklistEntity::getOwnerUserId, ownerUserId)
                        .eq(BlacklistEntity::getBlockUserId, blockedUserId));
                session.commit();
            }
            return null;
        });
    }

    @Override
    public List<String> getBlackList(String userId) {
        try (SqlSession session = MyBatisPlusFactory.openSession()) {
            BlacklistMapper mapper = session.getMapper(BlacklistMapper.class);
            return mapper.selectList(new LambdaQueryWrapper<BlacklistEntity>()
                    .eq(BlacklistEntity::getOwnerUserId, userId)).stream()
                    .map(BlacklistEntity::getBlockUserId).toList();
        }
    }

    // ── 转换 ──

    private FriendApply toFriendApply(FriendRequestEntity entity) {
        FriendApply apply = new FriendApply();
        apply.setFromUserId(entity.getFromUserId());
        apply.setToUserId(entity.getToUserId());
        apply.setReqMsg(entity.getReqMsg());
        apply.setHandlerUserId(entity.getHandlerUserId());
        apply.setHandleMsg(entity.getHandleMsg());
        apply.setHandleResult(entity.getHandleResult());
        apply.setCreateTime(entity.getCreatedAt());
        apply.setHandleTime(entity.getHandleTime());
        return apply;
    }

    private FriendInformation toFriendInformation(FriendEntity entity) {
        FriendInformation fi = new FriendInformation();
        fi.setOwnerUserId(entity.getOwnerUserId());
        fi.setFriendUserId(entity.getFriendUserId());
        fi.setRemark(entity.getRemark());
        fi.setAddSource(entity.getAddSource());
        fi.setEx(entity.getEx());
        fi.setCreateTime(entity.getCreatedAt());
        return fi;
    }
}
