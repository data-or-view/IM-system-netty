package com.im.api;

import java.util.Collections;
import java.util.List;

/**
 * 增量同步结果。
 *
 * <p>客户端与服务端之间的数据增量同步契约。
 * 客户端保存上次同步的 version，下次请求时带上，
 * 服务端返回该 version 之后的所有变更记录。</p>
 *
 * <p>设计要点：</p>
 * <ul>
 *   <li>version 是单调递增的全局逻辑时钟（非时间戳），
 *       每次数据变更时 version +1</li>
 *   <li>客户端不需要理解 version 的含义，只需存储并回传</li>
 *   <li>hasMore 用于分页场景：一次同步不完时继续拉取</li>
 * </ul>
 *
 * @param <T> 同步的实体类型（FriendInformation / Conversation / GroupMemberInformation 等）
 */
public class IncrementalSyncResult<T> {

    private final List<T> entities;
    private final long latestVersion;
    private final boolean hasMore;

    public IncrementalSyncResult(List<T> entities, long latestVersion, boolean hasMore) {
        this.entities = entities != null ? entities : Collections.emptyList();
        this.latestVersion = latestVersion;
        this.hasMore = hasMore;
    }

    /** 变更的实体列表（含新增/更新/删除标记），空列表表示无变更。 */
    public List<T> getEntities() { return entities; }

    /** 当前最新版本号，客户端应保存此值用于下次增量请求。 */
    public long getLatestVersion() { return latestVersion; }

    /** 是否还有更多变更未拉取（客户端需继续请求直到 hasMore=false）。 */
    public boolean hasMore() { return hasMore; }

    /** 构造一个"无变更"的空结果。 */
    public static <T> IncrementalSyncResult<T> empty(long currentVersion) {
        return new IncrementalSyncResult<>(Collections.emptyList(), currentVersion, false);
    }
}
