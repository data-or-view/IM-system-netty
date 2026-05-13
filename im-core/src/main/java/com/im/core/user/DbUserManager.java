package com.im.core.user;

import com.im.api.ImErrorCode;
import com.im.api.ImException;
import com.im.api.IUserManager;
import com.im.api.UserInformation;
import com.im.core.db.MyBatisPlusFactory;
import com.im.core.db.entity.UserEntity;
import com.im.core.db.mapper.UserMapper;
import com.im.core.retry.RetryConfig;
import com.im.core.retry.RetryExecutor;
import com.im.core.retry.RetryStrategies;
import org.apache.ibatis.session.SqlSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 数据库用户管理器。
 *
 * <p>基于 MyBatis-Plus，所有用户数据读写 {@code im_users} 表。</p>
 */
public class DbUserManager implements IUserManager {

    private static final Logger log = LoggerFactory.getLogger(DbUserManager.class);
    private static final RetryConfig CFG = RetryStrategies.DB_WRITE;

    private final RetryExecutor retryExecutor;

    public DbUserManager(RetryExecutor retryExecutor) {
        this.retryExecutor = retryExecutor;
    }

    @Override
    public void register(String userId, String nickname, String faceUrl, String ex) {
        retryExecutor.execute(CFG, () -> {
            try (SqlSession session = MyBatisPlusFactory.openSession()) {
                UserMapper mapper = session.getMapper(UserMapper.class);
                UserEntity existing = mapper.selectById(userId);
                if (existing != null) {
                    throw new ImException(ImErrorCode.CONFLICT, "User already exists: " + userId);
                }
                UserEntity entity = new UserEntity();
                entity.setUserId(userId);
                entity.setNickname(nickname != null ? nickname : userId);
                entity.setFaceUrl(faceUrl);
                entity.setEx(ex);
                entity.setStatus(1);
                entity.setCreatedAt(System.currentTimeMillis());
                entity.setUpdatedAt(entity.getCreatedAt());
                mapper.insert(entity);
                session.commit();
                log.info("User registered: userId={}, nickname={}", userId, entity.getNickname());
            }
            return null;
        });
    }

    @Override
    public UserInformation getUserInformation(String userId) {
        try (SqlSession session = MyBatisPlusFactory.openSession()) {
            UserMapper mapper = session.getMapper(UserMapper.class);
            UserEntity entity = mapper.selectById(userId);
            if (entity == null) {
                throw new ImException(ImErrorCode.NOT_FOUND, "User not found: " + userId);
            }
            return toUserInformation(entity);
        }
    }

    @Override
    public List<UserInformation> getUsersInfo(List<String> userIds) {
        try (SqlSession session = MyBatisPlusFactory.openSession()) {
            UserMapper mapper = session.getMapper(UserMapper.class);
            return userIds.stream()
                    .map(mapper::selectById)
                    .filter(e -> e != null)
                    .map(this::toUserInformation)
                    .toList();
        }
    }

    @Override
    public Map<String, List<Integer>> getOnlineStatus(List<String> userIds) {
        // 在线状态由 RedisRouteTable 维护，此处返回空
        // 后续可通过 Redis 批量查询实现
        return Map.of();
    }

    @Override
    public void updateUserInformation(String userId, String nickname, String faceUrl,
                                      String ex, int globalRecvMsgOpt) {
        retryExecutor.execute(CFG, () -> {
            try (SqlSession session = MyBatisPlusFactory.openSession()) {
                UserMapper mapper = session.getMapper(UserMapper.class);
                UserEntity entity = mapper.selectById(userId);
                if (entity == null) {
                    throw new ImException(ImErrorCode.NOT_FOUND, "User not found: " + userId);
                }
                if (nickname != null) entity.setNickname(nickname);
                if (faceUrl != null) entity.setFaceUrl(faceUrl);
                if (ex != null) entity.setEx(ex);
                if (globalRecvMsgOpt >= 0) entity.setGlobalRecvMsgOpt(globalRecvMsgOpt);
                entity.setUpdatedAt(System.currentTimeMillis());
                mapper.updateById(entity);
                session.commit();
                log.info("User updated: userId={}", userId);
            }
            return null;
        });
    }

    @Override
    public List<UserInformation> searchUsers(String keyword, int limit) {
        try (SqlSession session = MyBatisPlusFactory.openSession()) {
            UserMapper mapper = session.getMapper(UserMapper.class);
            // 同时搜索 nickname 和 user_id
            return mapper.searchByKeyword(keyword, limit).stream()
                    .map(this::toUserInformation)
                    .toList();
        }
    }

    private UserInformation toUserInformation(UserEntity entity) {
        UserInformation info = new UserInformation(entity.getUserId(), entity.getNickname());
        info.setFaceUrl(entity.getFaceUrl());
        info.setEx(entity.getEx());
        info.setAppMangerLevel(entity.getAppMangerLevel());
        info.setGlobalRecvMsgOpt(entity.getGlobalRecvMsgOpt());
        info.setCreateTime(entity.getCreatedAt());
        info.setUpdatedAt(entity.getUpdatedAt());
        return info;
    }
}
