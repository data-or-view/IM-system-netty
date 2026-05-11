package com.im.core.conversation;

import com.im.api.Conversation;
import com.im.api.IConversationManager;
import com.im.api.IMCommand;
import com.im.api.cache.ICache;
import com.im.core.cache.ConcurrentHashCache;
import com.im.core.cache.SafeCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * 本地内存会话管理器（单机开发/测试用）。
 *
 * 数据模型：
 *   userId → [Conversation1, Conversation2, ...]
 *   每个 Conversation 按 lastMsgTime 降序排列
 *
 * 节点重启后数据丢失——生产环境请换 DB 实现。
 *
 * 可选的缓存层（SafeCache 包裹，任何异常降级到内存数据源）。
 */
public class LocalConversationManager implements IConversationManager {

    private static final Logger log = LoggerFactory.getLogger(LocalConversationManager.class);
    private static final long CONV_CACHE_TTL = 120; // 2分钟

    /** userId → 会话列表（有序，lastMsgTime 降序） */
    private final ConcurrentMap<String, CopyOnWriteArrayList<Conversation>> store = new ConcurrentHashMap<>();

    /** userId + conversationId → Conversation（快速查找） */
    private final ConcurrentMap<String, Conversation> index = new ConcurrentHashMap<>();

    /** 会话列表缓存（key=userId） */
    private final ICache<String, List<Conversation>> conversationListCache;

    public LocalConversationManager() {
        this(null);
    }

    public LocalConversationManager(ICache<String, List<Conversation>> conversationListCache) {
        this.conversationListCache = conversationListCache != null
                ? new SafeCache<>(conversationListCache, "LocalConversationManager")
                : null;
    }

    private static String indexKey(String userId, String conversationId) {
        return userId + "::" + conversationId;
    }

    @Override
    public List<Conversation> getConversations(String userId) {
        if (conversationListCache != null) {
            List<Conversation> cached = conversationListCache.getOrLoad(
                    convListKey(userId),
                    () -> buildConversationList(userId),
                    CONV_CACHE_TTL);
            return cached;
        }
        return buildConversationList(userId);
    }

    private List<Conversation> buildConversationList(String userId) {
        List<Conversation> list = store.get(userId);
        if (list == null || list.isEmpty()) {
            return Collections.emptyList();
        }
        // 按 lastMsgTime 降序
        return list.stream()
                .sorted((a, b) -> Long.compare(b.getLastMsgTime(), a.getLastMsgTime()))
                .collect(Collectors.toList());
    }

    @Override
    public Conversation getConversation(String userId, String conversationId) {
        return index.get(indexKey(userId, conversationId));
    }

    @Override
    public void updateOnMessage(String conversationId, String toUserId, IMCommand msg, boolean isSelf) {
        Conversation conv = getOrCreate(toUserId, conversationId);

        // 更新最后一条消息信息
        conv.setLastMsgSeq(msg.getSeqId());
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
        index.put(indexKey(toUserId, conversationId), conv);

        log.debug("Conversation updated: userId={}, conv={}, seq={}, unread={}",
                toUserId, conversationId, msg.getSeqId(), conv.getUnreadCount());
        invalidateUserConversationCache(toUserId);
    }

    @Override
    public void markRead(String userId, String conversationId, int readSeq) {
        Conversation conv = getConversation(userId, conversationId);
        if (conv != null) {
            conv.setUnreadCount(0);
            conv.setUpdateTime(System.currentTimeMillis());
            log.debug("Conversation markRead: userId={}, conv={}", userId, conversationId);
            invalidateUserConversationCache(userId);
        }
    }

    @Override
    public void setPinned(String userId, String conversationId, boolean pinned) {
        Conversation conv = getConversation(userId, conversationId);
        if (conv != null) {
            conv.setPinned(pinned);
            conv.setUpdateTime(System.currentTimeMillis());
            log.info("Conversation pin: userId={}, conv={}, pinned={}", userId, conversationId, pinned);
            invalidateUserConversationCache(userId);
        }
    }

    @Override
    public void setRecvMsgOpt(String userId, String conversationId, int recvMsgOpt) {
        Conversation conv = getConversation(userId, conversationId);
        if (conv != null) {
            conv.setRecvMsgOpt(recvMsgOpt);
            conv.setUpdateTime(System.currentTimeMillis());
            log.info("Conversation recvMsgOpt: userId={}, conv={}, opt={}", userId, conversationId, recvMsgOpt);
            invalidateUserConversationCache(userId);
        }
    }

    // ── 缓存失效 ──

    private void invalidateUserConversationCache(String userId) {
        if (conversationListCache != null) {
            conversationListCache.delete(convListKey(userId));
        }
    }

    private static String convListKey(String userId) {
        return "conv:" + userId;
    }

    // ========== private ==========

    private Conversation getOrCreate(String userId, String conversationId) {
        String key = indexKey(userId, conversationId);
        return index.computeIfAbsent(key, k -> {
            int sessionType = conversationId.startsWith("group_")
                    ? Conversation.SESSION_TYPE_GROUP
                    : Conversation.SESSION_TYPE_SINGLE;

            Conversation conv = new Conversation(conversationId, userId, sessionType);
            // 添加有序列表
            store.computeIfAbsent(userId, u -> new CopyOnWriteArrayList<>()).add(conv);

            log.debug("Conversation created: userId={}, conv={}, type={}",
                    userId, conversationId, sessionType == Conversation.SESSION_TYPE_GROUP ? "group" : "single");
            return conv;
        });
    }

    /**
     * 提取消息内容前 100 字符作为会话预览。
     */
    private String extractContentPreview(IMCommand msg) {
        byte[] body = msg.getBody();
        if (body == null || body.length == 0) {
            return "";
        }
        String raw = new String(body, java.nio.charset.StandardCharsets.UTF_8);
        if (raw.length() > 100) {
            return raw.substring(0, 100) + "...";
        }
        return raw;
    }
}
