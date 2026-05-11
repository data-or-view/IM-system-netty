package com.im.core.store;

import com.im.api.IMCommand;
import com.im.api.IMessageStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 本地内存消息存储（单机开发/测试用）。
 *
 * 双索引存储：
 *   · Conversation 存储：conversationId → List(IMCommand)（按 seq 有序，基于 _ms 头）
 *   · 离线消息队列：userId → Queue(IMCommand)（FIFO，上限 1000 条）
 *
 * 保存时同时写入两个索引：
 *   save(msg) → conversationStore + (对应用户的) offlineQueue
 *
 * 查询：
 *   pullBySequence(conversationId, start, end, limit) → 从 conversationStore 按 seq 筛选
 *   pullOffline(userId, limit) → 从 offlineQueue 消费
 *
 * 节点重启后所有数据丢失——生产环境请换 DB 实现。
 */
public class LocalMessageStore implements IMessageStore {

    private static final Logger log = LoggerFactory.getLogger(LocalMessageStore.class);

    /** 消息 seq 头 */
    private static final String HEADER_MSG_SEQ = "_ms";

    /** conversationId → 消息列表（按 seq 升序追加） */
    private final ConcurrentMap<String, CopyOnWriteArrayList<IMCommand>> conversationStore = new ConcurrentHashMap<>();

    /** userId → 离线消息队列（FIFO） */
    private final ConcurrentMap<String, ConcurrentLinkedQueue<IMCommand>> offlineStore = new ConcurrentHashMap<>();

    /** 每个用户最多缓存的离线消息数 */
    private static final int MAX_OFFLINE_PER_USER = 1000;

    @Override
    public void save(IMCommand msg) {
        // 1. 按 conversation 存储（用于 seq 拉取）
        String conversationId = conversationId(msg);
        if (conversationId != null) {
            conversationStore.computeIfAbsent(conversationId, k -> new CopyOnWriteArrayList<>()).add(msg);
        }

        // 2. 按用户存储离线消息（用于用户离线时暂存）
        String toUserId = msg.getHeader("toUserId");
        String groupId = msg.getHeader("groupId");

        if (toUserId != null) {
            offlineStore.compute(toUserId, (userId, queue) -> {
                if (queue == null) {
                    queue = new ConcurrentLinkedQueue<>();
                }
                queue.add(msg);
                while (queue.size() > MAX_OFFLINE_PER_USER) {
                    queue.poll();
                }
                return queue;
            });
        } else if (groupId != null) {
            // 群聊：按群成员展开后存... 当前简化，以后 IGroupManager 完成后再优化
            // 现在先不存离线，等群聊成员展开后独立处理
            log.debug("Group msg {} not stored offline (group thread TBD)", msg.getMessageId());
        }
    }

    @Override
    public List<IMCommand> pullBySequence(String conversationId, long startSeq, long endSeq, int limit) {
        List<IMCommand> messages = conversationStore.get(conversationId);
        if (messages == null || messages.isEmpty()) {
            return Collections.emptyList();
        }

        int actualLimit = (limit <= 0) ? 50 : limit;
        long actualStart = (startSeq <= 0) ? Long.MIN_VALUE : startSeq;
        long actualEnd = (endSeq <= 0) ? Long.MAX_VALUE : endSeq;

        List<IMCommand> result = new ArrayList<>(actualLimit);
        messages.sort(Comparator.comparingLong(a -> seqOf(a))); // 确保有序

        for (IMCommand msg : messages) {
            long seq = seqOf(msg);
            if (seq >= actualStart && seq <= actualEnd) {
                result.add(msg);
                if (result.size() >= actualLimit) {
                    break;
                }
            }
        }

        return result;
    }

    @Override
    public List<IMCommand> pullOffline(String userId, int limit) {
        ConcurrentLinkedQueue<IMCommand> queue = offlineStore.get(userId);
        if (queue == null || queue.isEmpty()) {
            return Collections.emptyList();
        }

        List<IMCommand> result = new ArrayList<>(Math.min(limit, queue.size()));
        for (int i = 0; i < limit && !queue.isEmpty(); i++) {
            IMCommand msg = queue.poll();
            log.debug("Pulling offline msg for user {}: messageId={}, from={}",
                    userId, msg.getMessageId(), msg.getHeader("fromUserId"));
            result.add(msg);
        }
        return result;
    }

    @Override
    public void markDelivered(String userId, List<String> msgIds) {
        // pullOffline 已消费即移除，无需额外操作
        log.debug("Marked {} messages delivered for user {}", msgIds.size(), userId);
    }

    @Override
    public void deleteBefore(String userId, long seqId) {
        for (Map.Entry<String, CopyOnWriteArrayList<IMCommand>> entry : conversationStore.entrySet()) {
            entry.getValue().removeIf(msg -> {
                String toUserId = msg.getHeader("toUserId");
                return userId.equals(toUserId) && seqOf(msg) < seqId;
            });
        }
    }

    // ========== helpers ==========

    /**
     * 从 IMCommand 中提取 _ms 头作为 long。
     */
    private long seqOf(IMCommand msg) {
        String s = msg.getHeader(HEADER_MSG_SEQ);
        if (s != null) {
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException ignored) {
            }
        }
        return 0;
    }

    /**
     * 构造 conversation ID。
     * 单聊：single_{userA}_{userB}（字母序拼接）
     * 群聊：group_{groupId}
     */
    private String conversationId(IMCommand msg) {
        String groupId = msg.getHeader("groupId");
        if (groupId != null) {
            return "group_" + groupId;
        }

        String fromUserId = msg.getHeader("fromUserId");
        String toUserId = msg.getHeader("toUserId");
        if (fromUserId != null && toUserId != null) {
            String user1 = fromUserId.compareTo(toUserId) <= 0 ? fromUserId : toUserId;
            String user2 = fromUserId.compareTo(toUserId) <= 0 ? toUserId : fromUserId;
            return "single_" + user1 + "_" + user2;
        }

        return null;
    }

    /** 仅供测试用：获取 conversation 数量 */
    int conversationCount() {
        return conversationStore.size();
    }

    /** 仅供测试用：清空所有数据 */
    void clear() {
        conversationStore.clear();
        offlineStore.clear();
    }
}
