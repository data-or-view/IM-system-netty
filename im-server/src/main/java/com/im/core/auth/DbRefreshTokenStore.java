package com.im.core.auth;

import com.im.api.IRefreshTokenStore;
import com.im.api.RefreshTokenRecord;
import com.im.common.exception.PersistenceExceptions;
import com.im.common.retry.RetryConfig;
import com.im.common.retry.RetryExecutor;
import com.im.common.retry.RetryStrategies;
import com.im.core.db.MyBatisPlusFactory;
import com.im.core.db.entity.RefreshTokenEntity;
import com.im.core.db.mapper.RefreshTokenMapper;
import org.apache.ibatis.session.SqlSession;

public class DbRefreshTokenStore implements IRefreshTokenStore {

    private static final RetryConfig CFG = RetryStrategies.DB_WRITE;

    private final RetryExecutor retryExecutor;

    public DbRefreshTokenStore(RetryExecutor retryExecutor) {
        this.retryExecutor = retryExecutor;
    }

    @Override
    public void save(String tokenId, String userId, String tokenHash, int appManagerLevel,
                     long issuedAt, long expiresAt) {
        PersistenceExceptions.runDatabase("save refresh token", () -> retryExecutor.execute(CFG, () -> {
            try (SqlSession session = MyBatisPlusFactory.openSession()) {
                RefreshTokenMapper mapper = session.getMapper(RefreshTokenMapper.class);
                RefreshTokenEntity entity = new RefreshTokenEntity();
                entity.setTokenId(tokenId);
                entity.setUserId(userId);
                entity.setTokenHash(tokenHash);
                entity.setAppMangerLevel(appManagerLevel);
                entity.setIssuedAt(issuedAt);
                entity.setExpiresAt(expiresAt);
                entity.setRevokedAt(0);
                mapper.insert(entity);
                session.commit();
            }
            return null;
        }));
    }

    @Override
    public RefreshTokenRecord findActive(String tokenId) {
        return PersistenceExceptions.runDatabase("find refresh token", () -> {
            try (SqlSession session = MyBatisPlusFactory.openSession()) {
                RefreshTokenMapper mapper = session.getMapper(RefreshTokenMapper.class);
                RefreshTokenEntity entity = mapper.selectById(tokenId);
                if (entity == null || entity.getRevokedAt() > 0 || entity.getExpiresAt() <= System.currentTimeMillis()) {
                    return null;
                }
                return toRecord(entity);
            }
        });
    }

    @Override
    public void revoke(String tokenId, long revokedAt) {
        PersistenceExceptions.runDatabase("revoke refresh token", () -> retryExecutor.execute(CFG, () -> {
            try (SqlSession session = MyBatisPlusFactory.openSession()) {
                RefreshTokenMapper mapper = session.getMapper(RefreshTokenMapper.class);
                RefreshTokenEntity entity = mapper.selectById(tokenId);
                if (entity != null && entity.getRevokedAt() <= 0) {
                    entity.setRevokedAt(revokedAt);
                    mapper.updateById(entity);
                    session.commit();
                }
            }
            return null;
        }));
    }

    private RefreshTokenRecord toRecord(RefreshTokenEntity entity) {
        return new RefreshTokenRecord(
                entity.getTokenId(),
                entity.getUserId(),
                entity.getTokenHash(),
                entity.getAppMangerLevel(),
                entity.getIssuedAt(),
                entity.getExpiresAt(),
                entity.getRevokedAt());
    }
}
