package com.im.core.sync;

import com.im.api.IncrementalSyncResult;
import com.im.core.db.MyBatisPlusFactory;
import com.im.core.db.entity.SyncChangeEntity;
import com.im.core.db.mapper.SyncChangeMapper;
import com.im.core.db.mapper.SyncVersionMapper;
import com.im.common.retry.RetryConfig;
import com.im.common.retry.RetryExecutor;
import com.im.common.retry.RetryStrategies;
import org.apache.ibatis.session.SqlSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

/**
 * 数据库版增量同步辅助类。
 *
 * <p>提供原子版本递增和变更日志写入/查询能力，
 * 供 {@code DbFriendManager}、{@code DbGroupManager}、{@code DbConversationManager} 使用。</p>
 */
public class DbIncrementalSync {

    private static final Logger log = LoggerFactory.getLogger(DbIncrementalSync.class);
    private static final RetryConfig CFG = RetryStrategies.DB_WRITE;
    private static final int DEFAULT_LIMIT = 200;

    private static final String ACTION_INSERT = "insert";
    private static final String ACTION_UPDATE = "update";
    private static final String ACTION_DELETE = "delete";

    private final RetryExecutor retryExecutor;

    public DbIncrementalSync(RetryExecutor retryExecutor) {
        this.retryExecutor = retryExecutor;
    }

    /**
     * 原子递增版本号并记录变更。
     *
     * @param userId     用户 ID
     * @param entityType 实体类型
     * @param entityId   实体 ID
     * @param action     变更类型：insert / update / delete
     */
    public void recordChange(String userId, String entityType, String entityId, String action) {
        retryExecutor.execute(CFG, () -> {
            try (SqlSession session = MyBatisPlusFactory.openSession()) {
                SyncVersionMapper versionMapper = session.getMapper(SyncVersionMapper.class);
                SyncChangeMapper changeMapper = session.getMapper(SyncChangeMapper.class);

                versionMapper.incrementVersion(userId, entityType);
                long version = versionMapper.getVersion(userId, entityType);

                SyncChangeEntity change = new SyncChangeEntity();
                change.setUserId(userId);
                change.setEntityType(entityType);
                change.setEntityId(entityId);
                change.setVersion(version);
                change.setAction(action);
                change.setCreatedAt(System.currentTimeMillis());
                changeMapper.insertChange(change);

                session.commit();
            }
            return null;
        });
    }

    /**
     * 查询增量变更（带 entity_id 列表）并构建结果。
     *
     * @param userId        用户 ID
     * @param entityType    实体类型
     * @param sinceVersion  客户端已知版本
     * @param entityMapper  将 entityId 转换为实体对象的函数（insert/update 时调用，delete 时 entityId->null）
     * @param entityBuilder 根据 entityId 构建一个标记为 deleted 的实体对象
     * @param <T>           实体类型
     * @return 增量同步结果
     */
    public <T> IncrementalSyncResult<T> getChanges(
            String userId, String entityType, long sinceVersion,
            Function<String, T> entityMapper,
            Function<String, T> deletedEntityBuilder) {
        return getChanges(userId, entityType, sinceVersion, entityMapper, deletedEntityBuilder, DEFAULT_LIMIT);
    }

    /**
     * 查询增量变更（全量数据通过回调提供）。
     *
     * @param userId        用户 ID
     * @param entityType    实体类型
     * @param sinceVersion  客户端已知版本
     * @param entityMapper  将 entityId 转换为实体对象的函数（insert/update 时调用）
     * @param deletedEntityBuilder 根据 entityId 构建 deleted= true 的实体对象
     * @param limit         单次最大返回条数
     * @param <T>           实体类型
     * @return 增量同步结果
     */
    public <T> IncrementalSyncResult<T> getChanges(
            String userId, String entityType, long sinceVersion,
            Function<String, T> entityMapper,
            Function<String, T> deletedEntityBuilder,
            int limit) {
        try (SqlSession session = MyBatisPlusFactory.openSession()) {
            SyncVersionMapper versionMapper = session.getMapper(SyncVersionMapper.class);
            SyncChangeMapper changeMapper = session.getMapper(SyncChangeMapper.class);

            long currentVersion = versionMapper.getVersion(userId, entityType);
            if (currentVersion <= sinceVersion) {
                return IncrementalSyncResult.empty(currentVersion);
            }

            List<SyncChangeEntity> changes = changeMapper.selectChangesSince(
                    userId, entityType, sinceVersion, limit);

            if (changes.isEmpty()) {
                return IncrementalSyncResult.empty(currentVersion);
            }

            List<T> entities = new ArrayList<>(changes.size());
            for (SyncChangeEntity change : changes) {
                T entity;
                if (ACTION_DELETE.equals(change.getAction())) {
                    entity = deletedEntityBuilder.apply(change.getEntityId());
                } else {
                    entity = entityMapper.apply(change.getEntityId());
                }
                if (entity != null) {
                    entities.add(entity);
                }
            }

            long latestVersion = changes.get(changes.size() - 1).getVersion();
            boolean hasMore = changes.size() >= limit && currentVersion > latestVersion;

            return new IncrementalSyncResult<>(entities, latestVersion, hasMore);
        } catch (Exception e) {
            log.warn("Failed to get incremental changes for {} {} since {}: {}",
                    userId, entityType, sinceVersion, e.getMessage());
            return IncrementalSyncResult.empty(0);
        }
    }

    /**
     * 查询增量变更（返回 entityId 列表，不构建实体）。
     * 适用于 blacks、groups 等只需 ID 列表的场景。
     */
    public IncrementalSyncResult<String> getChangesAsIds(
            String userId, String entityType, long sinceVersion) {
        return getChangesAsIds(userId, entityType, sinceVersion, DEFAULT_LIMIT);
    }

    /**
     * 查询增量变更（返回 entityId 列表），含分页。
     */
    public IncrementalSyncResult<String> getChangesAsIds(
            String userId, String entityType, long sinceVersion, int limit) {
        try (SqlSession session = MyBatisPlusFactory.openSession()) {
            SyncVersionMapper versionMapper = session.getMapper(SyncVersionMapper.class);
            SyncChangeMapper changeMapper = session.getMapper(SyncChangeMapper.class);

            long currentVersion = versionMapper.getVersion(userId, entityType);
            if (currentVersion <= sinceVersion) {
                return IncrementalSyncResult.empty(currentVersion);
            }

            List<SyncChangeEntity> changes = changeMapper.selectChangesSince(
                    userId, entityType, sinceVersion, limit);

            if (changes.isEmpty()) {
                return IncrementalSyncResult.empty(currentVersion);
            }

            List<String> entities = new ArrayList<>(changes.size());
            for (SyncChangeEntity change : changes) {
                if (ACTION_DELETE.equals(change.getAction())) {
                    entities.add(change.getEntityId());
                } else {
                    entities.add(change.getEntityId());
                }
            }

            long latestVersion = changes.get(changes.size() - 1).getVersion();
            boolean hasMore = changes.size() >= limit && currentVersion > latestVersion;

            return new IncrementalSyncResult<>(entities, latestVersion, hasMore);
        } catch (Exception e) {
            log.warn("Failed to get incremental changes as IDs for {} {} since {}: {}",
                    userId, entityType, sinceVersion, e.getMessage());
            return IncrementalSyncResult.empty(0);
        }
    }
}
