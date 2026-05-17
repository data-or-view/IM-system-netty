package com.im.core.sync;

import com.im.api.IncrementalSyncResult;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * 内存版增量同步辅助类。
 *
 * <p>供 {@code LocalFriendManager}、{@code LocalGroupManager}、{@code LocalConversationManager} 使用。
 * 使用 ConcurrentHashMap + AtomicLong 存储版本计数，CopyOnWriteArrayList 存储变更日志。</p>
 */
public class LocalIncrementalSync {

    private static final int DEFAULT_LIMIT = 200;

    /** key = userId + ":" + entityType → 版本计数器 */
    private final ConcurrentMap<String, AtomicLong> versionCounters = new ConcurrentHashMap<>();

    /** key = userId + ":" + entityType → 变更日志列表 */
    private final ConcurrentMap<String, CopyOnWriteArrayList<ChangeRecord>> changeLogs = new ConcurrentHashMap<>();

    public LocalIncrementalSync() {
    }

    private String key(String userId, String entityType) {
        return userId + ":" + entityType;
    }

    /**
     * 递增版本号并记录变更。
     *
     * @param userId     用户 ID
     * @param entityType 实体类型
     * @param entityId   实体 ID
     * @param action     insert / update / delete
     * @return 新版本号
     */
    public long recordChange(String userId, String entityType, String entityId, String action) {
        String k = key(userId, entityType);
        AtomicLong counter = versionCounters.computeIfAbsent(k, _k -> new AtomicLong(0));
        long version = counter.incrementAndGet();
        changeLogs.computeIfAbsent(k, _k -> new CopyOnWriteArrayList<>())
                .add(new ChangeRecord(entityId, version, action));
        return version;
    }

    /**
     * 查询增量变更并使用 mapper 构建实体。
     */
    public <T> IncrementalSyncResult<T> getChanges(
            String userId, String entityType, long sinceVersion,
            Function<String, T> entityMapper,
            Function<String, T> deletedEntityBuilder) {
        return getChanges(userId, entityType, sinceVersion, entityMapper, deletedEntityBuilder, DEFAULT_LIMIT);
    }

    /**
     * 查询增量变更并使用 mapper 构建实体（含分页）。
     */
    public <T> IncrementalSyncResult<T> getChanges(
            String userId, String entityType, long sinceVersion,
            Function<String, T> entityMapper,
            Function<String, T> deletedEntityBuilder,
            int limit) {
        String k = key(userId, entityType);
        AtomicLong counter = versionCounters.get(k);
        long currentVersion = counter != null ? counter.get() : 0;

        if (currentVersion <= sinceVersion) {
            return IncrementalSyncResult.empty(currentVersion);
        }

        CopyOnWriteArrayList<ChangeRecord> logs = changeLogs.get(k);
        if (logs == null || logs.isEmpty()) {
            return IncrementalSyncResult.empty(currentVersion);
        }

        List<T> entities = new ArrayList<>();
        long latestVersion = sinceVersion;
        boolean hasMore = false;

        for (ChangeRecord record : logs) {
            if (record.version <= sinceVersion) continue;
            if (entities.size() >= limit) {
                hasMore = true;
                break;
            }
            T entity;
            if ("delete".equals(record.action)) {
                entity = deletedEntityBuilder.apply(record.entityId);
            } else {
                entity = entityMapper.apply(record.entityId);
            }
            if (entity != null) {
                entities.add(entity);
            }
            latestVersion = record.version;
        }

        return new IncrementalSyncResult<>(entities, latestVersion, hasMore);
    }

    /**
     * 查询增量变更（返回 entityId 列表，不构建实体）。
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
        String k = key(userId, entityType);
        AtomicLong counter = versionCounters.get(k);
        long currentVersion = counter != null ? counter.get() : 0;

        if (currentVersion <= sinceVersion) {
            return IncrementalSyncResult.empty(currentVersion);
        }

        CopyOnWriteArrayList<ChangeRecord> logs = changeLogs.get(k);
        if (logs == null || logs.isEmpty()) {
            return IncrementalSyncResult.empty(currentVersion);
        }

        List<String> entities = new ArrayList<>();
        long latestVersion = sinceVersion;
        boolean hasMore = false;

        for (ChangeRecord record : logs) {
            if (record.version <= sinceVersion) continue;
            if (entities.size() >= limit) {
                hasMore = true;
                break;
            }
            entities.add(record.entityId);
            latestVersion = record.version;
        }

        return new IncrementalSyncResult<>(entities, latestVersion, hasMore);
    }

    /**
     * 获取当前版本号。
     */
    public long getCurrentVersion(String userId, String entityType) {
        String k = key(userId, entityType);
        AtomicLong counter = versionCounters.get(k);
        return counter != null ? counter.get() : 0;
    }

    /**
     * 变更记录。
     */
    public record ChangeRecord(String entityId, long version, String action) {}
}
