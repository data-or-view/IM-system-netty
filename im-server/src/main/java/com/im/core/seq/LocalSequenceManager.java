package com.im.core.seq;

import com.im.api.ISequenceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 本地内存序号管理器（单机开发/测试用）。
 *
 * 用 ConcurrentHashMap<String, AtomicLong> 维护每个 conversation 的当前最大 seq。
 * 节点重启后 seq 归零——生产环境请换 RedisSeqManager（利用 INCR 原子性）。
 *
 * 参考 OpenIM 的 seq 分配（MongoDB 的 findAndModify / Redis INCR）：
 *   每个 conversation 独立计数，确保单点可靠递增。
 */
public class LocalSequenceManager implements ISequenceManager {

    private static final Logger log = LoggerFactory.getLogger(LocalSequenceManager.class);

    private final ConcurrentMap<String, AtomicLong> seqMap = new ConcurrentHashMap<>();

    @Override
    public long nextSequence(String conversationId) {
        AtomicLong seq = seqMap.computeIfAbsent(conversationId, k -> new AtomicLong(0));
        long next = seq.incrementAndGet();
        log.debug("Allocated seq {} for conversation {}", next, conversationId);
        return next;
    }

    @Override
    public long getMaximumSequence(String conversationId) {
        AtomicLong seq = seqMap.get(conversationId);
        return seq != null ? seq.get() : 0;
    }

    @Override
    public long[] getMaximumSequences(String[] conversationIds) {
        long[] result = new long[conversationIds.length];
        for (int i = 0; i < conversationIds.length; i++) {
            result[i] = getMaximumSequence(conversationIds[i]);
        }
        return result;
    }

    /**
     * 获取当前所有 conversation 的数量（用于监控）。
     */
    public int conversationCount() {
        return seqMap.size();
    }
}
