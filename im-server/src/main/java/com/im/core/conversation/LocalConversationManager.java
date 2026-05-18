package com.im.core.conversation;

import com.im.api.Conversation;
import com.im.api.IConversationManager;
import com.im.api.IncrementalSyncResult;
import com.im.api.Message;
import com.im.core.cache.Cache;
import com.im.core.cache.ConcurrentHashCache;
import com.im.core.cache.SafeCache;
import com.im.core.sync.LocalIncrementalSync;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * 本地内存会话管理器（单机开发/测试用）。
 *
 * 数据模型：
 *   ownerUserId → [Conversation1, Conversation2, ...]
 *   每个 Conversation 按 lastMsgTime 降序排列
 *
 * 节点重启后数据丢失——生产环境请换 DB 实现。
 *
 * 可选的缓存层（SafeCache 包裹，任何异常降级到内存数据源）。
 */
public class LocalConversationManager implements IConversationManager {

    private static final Logger log = LoggerFactory.getLogger(LocalConversationManager.class);
    private static final long CONV_CACHE_TTL = 120; // 2分钟

    /** ownerUserId → 会话列表（有序，lastMsgTime 降序） */
    private final ConcurrentMap<String, CopyOnWriteArrayList<Conversation>> store = new ConcurrentHashMap<>();

    /** ownerUserId + conversationId → Conversation（快速查找） */
    private final ConcurrentMap<String, Conversation> index = new ConcurrentHashMap<>();

    /** ownerUserId::conversationId → readSeq */
    private final ConcurrentMap<String, Long> readSeqStore = new ConcurrentHashMap<>();

    /** 会话列表缓存（key=ownerUserId） */
    private final Cache<String, List<Conversation>> conversationListCache;

    /** 增量同步追踪 */
    private final LocalIncrementalSync sync;

    public LocalConversationManager() {
        this(null, new LocalIncrementalSync());
    }

    public LocalConversationManager(Cache<String, List<Conversation>> conversationListCache) {
        this(conversationListCache, new LocalIncrementalSync());
    }

    public LocalConversationManager(Cache<String, List<Conversation>> conversationListCache,
                                    LocalIncrementalSync sync) {
        this.conversationListCache = conversationListCache != null
                ? new SafeCache<>(conversationListCache, "LocalConversationManager")
                : null;
        this.sync = sync;
    }

    private static String indexKey(String ownerUserId, String conversationId) {
        return ownerUserId + "::" + conversationId;
    }

    @Override
    public List<Conversation> getConversations(String ownerUserId) {
        if (conversationListCache != null) {
            return conversationListCache.get(convListKey(ownerUserId)).orElseGet(() -> {
                List<Conversation> list = buildConversationList(ownerUserId);
                conversationListCache.put(convListKey(ownerUserId), list);
                return list;
            });
        }
        return buildConversationList(ownerUserId);
    }

    private List<Conversation> buildConversationList(String ownerUserId) {
        List<Conversation> list = store.get(ownerUserId);
        if (list == null || list.isEmpty()) {
            return Collections.emptyList();
        }
        // 按 lastMsgTime 降序
        return list.stream()
                .sorted((a, b) -> Long.compare(b.getLastMsgTime(), a.getLastMsgTime()))
                .collect(Collectors.toList());
    }

    @Override
    public Conversation getConversation(String ownerUserId, String conversationId) {
        return index.get(indexKey(ownerUserId, conversationId));
    }

    @Override
    public void updateOnMessage(String ownerUserId, String conversationId, Message msg, boolean isSelf) {
        Conversation conv = getOrCreate(ownerUserId, conversationId);

        // 更新最后一条消息信息
        conv.setLastMsgSeq((int) msg.getSequenceId());
        conv.setLastMsgId(msg.getMessageId());
        conv.setLastMsgTime(System.currentTimeMillis());
        conv.setUpdateTime(conv.getLastMsgTime());

        // 消息内容预览（取前 100 字符）
        String content = extractContentPreview(msg);
        conv.setLastMsgContent(content);

        // 未读数：自己发的消息不加，别人发的才 +1
        if (!isSelf) {
            conv.setUnreadCount(conv.getUnreadCount() + 1);
        }

        // 更新索引
        index.put(indexKey(ownerUserId, conversationId), conv);
        sync.recordChange(ownerUserId, "conversation", conversationId, "update");

        log.debug("Conversation updated: ownerUserId={}, conv={}, seq={}, unread={}",
                ownerUserId, conversationId, msg.getSequenceId(), conv.getUnreadCount());
        invalidateUserConversationCache(ownerUserId);
    }

    @Override
    public void markRead(String ownerUserId, String conversationId, long readSeq) {
        Conversation conv = getConversation(ownerUserId, conversationId);
        if (conv != null) {
            conv.setUnreadCount(0);
            conv.setUpdateTime(System.currentTimeMillis());
            sync.recordChange(ownerUserId, "conversation", conversationId, "update");
            log.debug("Conversation markRead: ownerUserId={}, conv={}", ownerUserId, conversationId);
            invalidateUserConversationCache(ownerUserId);
        }
        readSeqStore.put(indexKey(ownerUserId, conversationId), readSeq);
    }

    @Override
    public int getUnreadCount(String ownerUserId, String conversationId) {
        Conversation conv = getConversation(ownerUserId, conversationId);
        return conv != null ? (int) conv.getUnreadCount() : 0;
    }

    @Override
    public int getTotalUnreadCount(String userId) {
        List<Conversation> convs = getConversations(userId);
        return convs.stream().mapToInt(c -> (int) c.getUnreadCount()).sum();
    }

    @Override
    public long getReadSeq(String ownerUserId, String conversationId) {
        return readSeqStore.getOrDefault(indexKey(ownerUserId, conversationId), 0L);
    }

    @Override
    public void setPinned(String ownerUserId, String conversationId, boolean pinned) {
        Conversation conv = getConversation(ownerUserId, conversationId);
        if (conv != null) {
            conv.setPinned(pinned);
            conv.setUpdateTime(System.currentTimeMillis());
            sync.recordChange(ownerUserId, "conversation", conversationId, "update");
            log.info("Conversation pin: ownerUserId={}, conv={}, pinned={}", ownerUserId, conversationId, pinned);
            invalidateUserConversationCache(ownerUserId);
        }
    }

    @Override
    public void setRecvMsgOpt(String ownerUserId, String conversationId, int recvMsgOpt) {
        Conversation conv = getConversation(ownerUserId, conversationId);
        if (conv != null) {
            conv.setRecvMsgOpt(recvMsgOpt);
            conv.setUpdateTime(System.currentTimeMillis());
            sync.recordChange(ownerUserId, "conversation", conversationId, "update");
            log.info("Conversation recvMsgOpt: ownerUserId={}, conv={}, opt={}", ownerUserId, conversationId, recvMsgOpt);
            invalidateUserConversationCache(ownerUserId);
        }
    }

    @Override
    public void setBurnDuration(String ownerUserId, String conversationId, int burnDuration) {
        Conversation conv = getConversation(ownerUserId, conversationId);
        if (conv != null) {
            conv.setBurnDuration(burnDuration);
            conv.setUpdateTime(System.currentTimeMillis());
            sync.recordChange(ownerUserId, "conversation", conversationId, "update");
            log.info("Conversation burnDuration: ownerUserId={}, conv={}, duration={}s",
                    ownerUserId, conversationId, burnDuration);
            invalidateUserConversationCache(ownerUserId);
        }
    }

    @Override
    public void createSingleConversation(String ownerUserId, String targetUserId, String conversationId) {
        getOrCreate(ownerUserId, conversationId);
        sync.recordChange(ownerUserId, "conversation", conversationId, "insert");
    }

    @Override
    public void createGroupConversations(List<String> memberIds, String groupId, String conversationId) {
        for (String memberId : memberIds) {
            getOrCreate(memberId, conversationId);
            sync.recordChange(memberId, "conversation", conversationId, "insert");
        }
        log.debug("Group conversations created: groupId={}, members={}", groupId, memberIds.size());
    }

    // ========== 增量同步 ==========

    @Override
    public IncrementalSyncResult<Conversation> getIncrementalConversations(String ownerUserId, long version) {
        return sync.getChanges(ownerUserId, "conversation", version,
                convId -> getConversation(ownerUserId, convId),
                convId -> null);
    }

    // ── 缓存失效 ──

    private void invalidateUserConversationCache(String ownerUserId) {
        if (conversationListCache != null) {
            conversationListCache.invalidate(convListKey(ownerUserId));
        }
    }

    private static String convListKey(String ownerUserId) {
        return "conv:" + ownerUserId;
    }

    // ========== private ==========

    private Conversation getOrCreate(String ownerUserId, String conversationId) {
        String key = indexKey(ownerUserId, conversationId);
        return index.computeIfAbsent(key, k -> {
            int sessionType = conversationId.startsWith("group_")
                    ? Conversation.SESSION_TYPE_GROUP
                    : Conversation.SESSION_TYPE_SINGLE;

            Conversation conv = new Conversation(conversationId, ownerUserId, sessionType);
            // 添加有序列表
            store.computeIfAbsent(ownerUserId, u -> new CopyOnWriteArrayList<>()).add(conv);

            log.debug("Conversation created: ownerUserId={}, conv={}, type={}",
                    ownerUserId, conversationId,
                    sessionType == Conversation.SESSION_TYPE_GROUP ? "group" : "single");
            return conv;
        });
    }

    /**
     * 提取消息内容前 100 字符作为会话预览。
     */
    private String extractContentPreview(Message msg) {
        byte[] body = msg.getBody();
        if (body == null || body.length == 0) {
            return "";
        }
        String raw = new String(body, StandardCharsets.UTF_8);
        if (raw.length() > 100) {
            return raw.substring(0, 100) + "...";
        }
        return raw;
    }
}
