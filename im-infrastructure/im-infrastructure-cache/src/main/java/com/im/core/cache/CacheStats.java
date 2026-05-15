package com.im.core.cache;

import java.util.Objects;

/**
 * 缓存统计信息快照。
 *
 * <p>不可变值对象，包含缓存的核心监控指标。
 */
public final class CacheStats {

    /** 空统计，适用于不支持统计的缓存实现 */
    public static final CacheStats EMPTY = new CacheStats(0, 0, 0, 0, 0);

    private final long hitCount;
    private final long missCount;
    private final long evictionCount;
    private final long loadSuccessCount;
    private final long loadFailureCount;

    /**
     * @param hitCount        命中次数
     * @param missCount       miss 次数
     * @param evictionCount   被淘汰条目数
     * @param loadSuccessCount 加载成功次数
     * @param loadFailureCount 加载失败次数
     */
    public CacheStats(long hitCount, long missCount,
                      long evictionCount, long loadSuccessCount,
                      long loadFailureCount) {
        this.hitCount = hitCount;
        this.missCount = missCount;
        this.evictionCount = evictionCount;
        this.loadSuccessCount = loadSuccessCount;
        this.loadFailureCount = loadFailureCount;
    }

    public long hitCount() {
        return hitCount;
    }

    public long missCount() {
        return missCount;
    }

    public long evictionCount() {
        return evictionCount;
    }

    public long loadSuccessCount() {
        return loadSuccessCount;
    }

    public long loadFailureCount() {
        return loadFailureCount;
    }

    /**
     * 命中率。
     *
     * @return 0.0 到 1.0，无请求时返回 1.0
     */
    public double hitRate() {
        long total = hitCount + missCount;
        return total == 0 ? 1.0 : (double) hitCount / total;
    }

    /**
     * 将当前统计与另一份统计相加，返回新的快照。
     */
    public CacheStats plus(CacheStats other) {
        Objects.requireNonNull(other);
        return new CacheStats(
                this.hitCount + other.hitCount,
                this.missCount + other.missCount,
                this.evictionCount + other.evictionCount,
                this.loadSuccessCount + other.loadSuccessCount,
                this.loadFailureCount + other.loadFailureCount
        );
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CacheStats that)) return false;
        return hitCount == that.hitCount
                && missCount == that.missCount
                && evictionCount == that.evictionCount
                && loadSuccessCount == that.loadSuccessCount
                && loadFailureCount == that.loadFailureCount;
    }

    @Override
    public int hashCode() {
        return Objects.hash(hitCount, missCount, evictionCount, loadSuccessCount, loadFailureCount);
    }

    @Override
    public String toString() {
        return "CacheStats{"
                + "hitCount=" + hitCount
                + ", missCount=" + missCount
                + ", hitRate=" + String.format("%.2f", hitRate())
                + ", evictionCount=" + evictionCount
                + ", loadSuccessCount=" + loadSuccessCount
                + ", loadFailureCount=" + loadFailureCount
                + '}';
    }
}
