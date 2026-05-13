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

    @Override
    public void applyAddFriend(String fromUserId, String toUserId, String reqMsg) {
        long now = System.currentTimeMillis();
        try (SqlSession session = MyBatisPlusFactory.openSession()) {
            FriendRequestMapper mapper = session.getMapper(FriendRequestMapper.class);

            // 检查是否有待处理的申请（不允许重复申请）
            LambdaQueryWrapper<FriendRequestEntity> qw = new LambdaQueryWrapper<>();
            qw.eq(FriendRequestEntity::getFromUserId, fromUserId)
                    .eq(FriendRequestEntity::getToUserId, toUserId)
                    .eq(FriendRequestEntity::getHandleResult, 0);
            if (mapper.selectCount(qw) > 0) {
                log.warn("Duplicate friend apply: {} -> {} already pending", fromUserId, toUserId);
                return;
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
    }

    @Override
    public void respondFriendApply(String userId, String fromUserId, String handleMsg, boolean agreed) {
        long now = System.currentTimeMillis();
        try (SqlSession session = MyBatisPlusFactory.openSession()) {
            FriendRequestMapper reqMapper = session.getMapper(FriendRequestMapper.class);
            FriendMapper friendMapper = session.getMapper(FriendMapper.class);

            // 查找待处理的申请
            LambdaQueryWrapper<FriendRequestEntity> qw = new LambdaQueryWrapper<>();
            qw.eq(FriendRequestEntity::getFromUserId, fromUserId)
                    .eq(FriendRequestEntity::getToUserId, userId)
                    .eq(FriendRequestEntity::getHandleResult, 0);
            FriendRequestEntity req = reqMapper.selectOne(qw);
            if (req == null) {
                log.warn("No pending friend apply from {} to {}", fromUserId, userId);
                return;
            }

            // 更新申请记录
            req.setHandleResult(agreed ? 1 : 2);
            req.setHandlerUserId(userId);
            req.setHandleMsg(handleMsg);
            req.setHandleTime(now);
            reqMapper.updateById(req);

            if (agreed) {
                // 双向建立好友关系
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
    }

    @Override
    public List<FriendApply> getFriendApplyList(String userId, boolean onlyPending) {
        try (SqlSession session = MyBatisPlusFactory.openSession()) {
            FriendRequestMapper mapper = session.getMapper(FriendRequestMapper.class);
            LambdaQueryWrapper<FriendRequestEntity> qw = new LambdaQueryWrapper<>();
            qw.eq(FriendRequestEntity::getToUserId, userId);
            if (onlyPending) {
                qw.eq(FriendRequestEntity::getHandleResult, 0);
            }
            qw.orderByDesc(FriendRequestEntity::getCreatedAt);
            return mapper.selectList(qw).stream()
                    .map(this::toFriendApply)
                    .toList();
        }
    }

    @Override
    public void deleteFriend(String ownerUserId, String friendUserId) {
        try (SqlSession session = MyBatisPlusFactory.openSession()) {
            FriendMapper mapper = session.getMapper(FriendMapper.class);
            // 双向删除
            LambdaQueryWrapper<FriendEntity> qw = new LambdaQueryWrapper<>();
            qw.eq(FriendEntity::getOwnerUserId, ownerUserId)
                    .eq(FriendEntity::getFriendUserId, friendUserId);
            mapper.delete(qw);
            qw = new LambdaQueryWrapper<>();
            qw.eq(FriendEntity::getOwnerUserId, friendUserId)
                    .eq(FriendEntity::getFriendUserId, ownerUserId);
            mapper.delete(qw);
            session.commit();
            log.info("Friend deleted: owner={}, friend={}", ownerUserId, friendUserId);
        }
    }

    @Override
    public List<FriendInformation> getFriendList(String userId) {
        try (SqlSession session = MyBatisPlusFactory.openSession()) {
            FriendMapper friendMapper = session.getMapper(FriendMapper.class);
            LambdaQueryWrapper<FriendEntity> qw = new LambdaQueryWrapper<>();
            qw.eq(FriendEntity::getOwnerUserId, userId);
            return friendMapper.selectList(qw).stream()
                    .map(this::toFriendInformation)
                    .toList();
        }
    }

    @Override
    public boolean isFriend(String userIdA, String userIdB) {
        try (SqlSession session = MyBatisPlusFactory.openSession()) {
            FriendMapper mapper = session.getMapper(FriendMapper.class);
            LambdaQueryWrapper<FriendEntity> qw = new LambdaQueryWrapper<>();
            qw.eq(FriendEntity::getOwnerUserId, userIdA)
                    .eq(FriendEntity::getFriendUserId, userIdB);
            return mapper.selectCount(qw) > 0;
        }
    }

    @Override
    public void setFriendRemark(String ownerUserId, String friendUserId, String remark) {
        try (SqlSession session = MyBatisPlusFactory.openSession()) {
            FriendMapper mapper = session.getMapper(FriendMapper.class);
            LambdaQueryWrapper<FriendEntity> qw = new LambdaQueryWrapper<>();
            qw.eq(FriendEntity::getOwnerUserId, ownerUserId)
                    .eq(FriendEntity::getFriendUserId, friendUserId);
            FriendEntity entity = mapper.selectOne(qw);
            if (entity != null) {
                entity.setRemark(remark);
                mapper.updateById(entity);
                session.commit();
            }
        }
    }

    @Override
    public void setFriendPinned(String ownerUserId, String friendUserId, boolean pinned) {
        try (SqlSession session = MyBatisPlusFactory.openSession()) {
            FriendMapper mapper = session.getMapper(FriendMapper.class);
            LambdaQueryWrapper<FriendEntity> qw = new LambdaQueryWrapper<>();
            qw.eq(FriendEntity::getOwnerUserId, ownerUserId)
                    .eq(FriendEntity::getFriendUserId, friendUserId);
            FriendEntity entity = mapper.selectOne(qw);
            if (entity != null) {
                entity.setIsPinned(pinned ? 1 : 0);
                mapper.updateById(entity);
                session.commit();
            }
        }
    }

    @Override
    public void addBlack(String ownerUserId, String blockedUserId) {
        try (SqlSession session = MyBatisPlusFactory.openSession()) {
            BlacklistMapper mapper = session.getMapper(BlacklistMapper.class);
            // 检查是否已拉黑
            LambdaQueryWrapper<BlacklistEntity> qw = new LambdaQueryWrapper<>();
            qw.eq(BlacklistEntity::getOwnerUserId, ownerUserId)
                    .eq(BlacklistEntity::getBlockUserId, blockedUserId);
            if (mapper.selectCount(qw) > 0) {
                return;
            }
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
    }

    @Override
    public void removeBlack(String ownerUserId, String blockedUserId) {
        try (SqlSession session = MyBatisPlusFactory.openSession()) {
            BlacklistMapper mapper = session.getMapper(BlacklistMapper.class);
            LambdaQueryWrapper<BlacklistEntity> qw = new LambdaQueryWrapper<>();
            qw.eq(BlacklistEntity::getOwnerUserId, ownerUserId)
                    .eq(BlacklistEntity::getBlockUserId, blockedUserId);
            mapper.delete(qw);
            session.commit();
        }
    }

    @Override
    public List<String> getBlackList(String userId) {
        try (SqlSession session = MyBatisPlusFactory.openSession()) {
            BlacklistMapper mapper = session.getMapper(BlacklistMapper.class);
            LambdaQueryWrapper<BlacklistEntity> qw = new LambdaQueryWrapper<>();
            qw.eq(BlacklistEntity::getOwnerUserId, userId);
            return mapper.selectList(qw).stream()
                    .map(BlacklistEntity::getBlockUserId)
                    .toList();
        }
    }

    @Override
    public boolean isBlocked(String fromUserId, String toUserId) {
        try (SqlSession session = MyBatisPlusFactory.openSession()) {
            BlacklistMapper mapper = session.getMapper(BlacklistMapper.class);
            LambdaQueryWrapper<BlacklistEntity> qw = new LambdaQueryWrapper<>();
            qw.eq(BlacklistEntity::getOwnerUserId, toUserId)
                    .eq(BlacklistEntity::getBlockUserId, fromUserId);
            return mapper.selectCount(qw) > 0;
        }
    }

    // ========== 实体 ⇔ DTO 转换 ==========

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
        fi.setPinned(entity.getIsPinned() == 1);
        fi.setCreateTime(entity.getCreatedAt());
        return fi;
    }
}
