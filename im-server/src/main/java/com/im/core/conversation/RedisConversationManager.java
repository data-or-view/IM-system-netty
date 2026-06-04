package com.im.core.conversation;

import com.im.api.Conversation;
import com.im.api.IConversationManager;
import com.im.api.IncrementalSyncResult;
import com.im.api.Message;
import com.im.common.exception.PersistenceExceptions;
import com.im.core.redis.RedisConfiguration;
import io.lettuce.core.cluster.api.async.RedisClusterAsyncCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Redis 会话管理器（生产环境用，无 DB 时的替代方案）。
 *
 * <p>数据模型：
 * <ul>
 *   <li>会话数据：conv:data:{ownerUserId}:{conversationId} → Hash</li>
 *   <li>会话列表：conv:list:{ownerUserId} → ZSet (score=lastMsgTime)</li>
 *   <li>版本号：sync:version:conv:{ownerUserId} → 数值（每次变更加 1）</li>
 * </ul>
 *
 * <p>与 {@link DbConversationManager} 不同，此实现不依赖 MySQL，
 * 在项目已启用 Redis 但未启用 DB 时替代 {@link LocalConversationManager}。</p>
 */
public class RedisConversationManager implements IConversationManager {

    private static final Logger log = LoggerFactory.getLogger(RedisConversationManager.class);

    private static final long REDIS_TIMEOUT_MS = 3000;

    private static final String KEY_DATA_PREFIX = "conv:data:";
    private static final String KEY_LIST_PREFIX = "conv:list:";
    private static final String KEY_VERSION_PREFIX = "sync:version:conv:";

    private final RedisClusterAsyncCommands<String, String> async;

    public RedisConversationManager(RedisConfiguration redisConfig) {
        this.async = redisConfig.async();
        log.info("RedisConversationManager initialized");
    }

    private static String dataKey(String ownerUserId, String conversationId) {
        return KEY_DATA_PREFIX + ownerUserId + ":" + conversationId;
    }

    private static String listKey(String ownerUserId) {
        return KEY_LIST_PREFIX + ownerUserId;
    }

    private static String versionKey(String ownerUserId) {
        return KEY_VERSION_PREFIX + ownerUserId;
    }

    @Override
    public List<Conversation> getConversations(String ownerUserId) {
        return PersistenceExceptions.runRedis("get conversations", () -> {
            List<String> convIds = async.zrevrange(listKey(ownerUserId), 0, -1)
                    .get(REDIS_TIMEOUT_MS, TimeUnit.MILLISECONDS);

            if (convIds == null || convIds.isEmpty()) return Collections.emptyList();

            List<Conversation> result = new ArrayList<>(convIds.size());
            for (String convId : convIds) {
                Conversation conv = getConversation(ownerUserId, convId);
                if (conv != null) result.add(conv);
            }
            return result;
        });
    }

    @Override
    public Conversation getConversation(String ownerUserId, String conversationId) {
        return PersistenceExceptions.runRedis("get conversation", () -> {
            Map<String, String> fields = async.hgetall(dataKey(ownerUserId, conversationId))
                    .get(REDIS_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (fields == null || fields.isEmpty()) return null;
            return hashToConversation(fields);
        });
    }

    @Override
    public void updateOnMessage(String ownerUserId, String conversationId, Message msg, boolean isSelf) {
        PersistenceExceptions.runRedis("update conversation on message", () -> {
            String key = dataKey(ownerUserId, conversationId);
            long now = System.currentTimeMillis();

            boolean exists = async.exists(key).get(REDIS_TIMEOUT_MS, TimeUnit.MILLISECONDS) == 1;
            if (!exists) {
                int sessionType = conversationId.startsWith("group_")
                        ? Conversation.SESSION_TYPE_GROUP : Conversation.SESSION_TYPE_SINGLE;
                Conversation conv = new Conversation(conversationId, ownerUserId, sessionType);
                if (sessionType == Conversation.SESSION_TYPE_SINGLE) {
                    String from = msg.getFromUserId();
                    conv.setUserId(from != null && from.equals(ownerUserId) ? msg.getToUserId() : from);
                } else {
                    conv.setGroupId(msg.getGroupId());
                }
                async.hset(key, conversationToHash(conv)).get(REDIS_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            }

            async.hset(key, "lastMsgSeq", String.valueOf(msg.getSequenceId()))
                    .get(REDIS_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (msg.getMessageId() != null) {
                async.hset(key, "lastMsgId", msg.getMessageId())
                        .get(REDIS_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            }
            async.hset(key, "lastMsgTime", String.valueOf(now))
                    .get(REDIS_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            async.hset(key, "updateTime", String.valueOf(now))
                    .get(REDIS_TIMEOUT_MS, TimeUnit.MILLISECONDS);

            String preview = extractContentPreview(msg);
            if (preview != null) {
                async.hset(key, "lastMsgContent", preview)
                        .get(REDIS_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            }

            if (!isSelf) {
                async.hincrby(key, "unreadCount", 1)
                        .get(REDIS_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            }

            async.zadd(listKey(ownerUserId), now, conversationId)
                    .get(REDIS_TIMEOUT_MS, TimeUnit.MILLISECONDS);

            incrVersion(ownerUserId);
            return null;
        });
    }

    @Override
    public void markRead(String ownerUserId, String conversationId, long readSeq) {
        PersistenceExceptions.runRedis("mark conversation read", () -> {
            String key = dataKey(ownerUserId, conversationId);
            long currentReadSeq = getReadSeq(ownerUserId, conversationId);
            long nextReadSeq = Math.max(currentReadSeq, readSeq);
            async.hset(key, "unreadCount", "0").get(REDIS_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            async.hset(key, "readSeq", String.valueOf(nextReadSeq)).get(REDIS_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            async.hset(key, "updateTime", String.valueOf(System.currentTimeMillis())).get(REDIS_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            incrVersion(ownerUserId);
            return null;
        });
    }

    @Override
    public void setPinned(String ownerUserId, String conversationId, boolean pinned) {
        PersistenceExceptions.runRedis("set conversation pinned", () -> {
            String key = dataKey(ownerUserId, conversationId);
            async.hset(key, "isPinned", pinned ? "1" : "0").get(REDIS_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            async.hset(key, "updateTime", String.valueOf(System.currentTimeMillis())).get(REDIS_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            incrVersion(ownerUserId);
            return null;
        });
    }

    @Override
    public void setRecvMsgOpt(String ownerUserId, String conversationId, int recvMsgOpt) {
        PersistenceExceptions.runRedis("set conversation receive option", () -> {
            String key = dataKey(ownerUserId, conversationId);
            async.hset(key, "recvMsgOpt", String.valueOf(recvMsgOpt)).get(REDIS_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            async.hset(key, "updateTime", String.valueOf(System.currentTimeMillis())).get(REDIS_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            incrVersion(ownerUserId);
            return null;
        });
    }

    @Override
    public void setBurnDuration(String ownerUserId, String conversationId, int burnDuration) {
        PersistenceExceptions.runRedis("set conversation burn duration", () -> {
            String key = dataKey(ownerUserId, conversationId);
            async.hset(key, "burnDuration", String.valueOf(burnDuration)).get(REDIS_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            async.hset(key, "updateTime", String.valueOf(System.currentTimeMillis())).get(REDIS_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            incrVersion(ownerUserId);
            return null;
        });
    }

    @Override
    public void createSingleConversation(String ownerUserId, String targetUserId, String conversationId) {
        PersistenceExceptions.runRedis("create single conversation", () -> {
            String key = dataKey(ownerUserId, conversationId);
            boolean exists = async.exists(key).get(REDIS_TIMEOUT_MS, TimeUnit.MILLISECONDS) == 1;
            if (exists) return null;

            long now = System.currentTimeMillis();
            Conversation conv = new Conversation(conversationId, ownerUserId, Conversation.SESSION_TYPE_SINGLE);
            conv.setUserId(targetUserId);
            conv.setCreateTime(now);
            conv.setUpdateTime(now);
            async.hset(key, conversationToHash(conv)).get(REDIS_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            async.zadd(listKey(ownerUserId), now, conversationId).get(REDIS_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            incrVersion(ownerUserId);
            return null;
        });
    }

    @Override
    public void createGroupConversations(List<String> memberIds, String groupId, String conversationId) {
        PersistenceExceptions.runRedis("create group conversations", () -> {
            long now = System.currentTimeMillis();
            for (String memberId : memberIds) {
                String key = dataKey(memberId, conversationId);
                boolean exists = async.exists(key).get(REDIS_TIMEOUT_MS, TimeUnit.MILLISECONDS) == 1;
                if (exists) continue;

                Conversation conv = new Conversation(conversationId, memberId, Conversation.SESSION_TYPE_GROUP);
                conv.setGroupId(groupId);
                conv.setCreateTime(now);
                conv.setUpdateTime(now);
                async.hset(key, conversationToHash(conv)).get(REDIS_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                async.zadd(listKey(memberId), now, conversationId).get(REDIS_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                incrVersion(memberId);
            }
            return null;
        });
    }

    @Override
    public long getReadSeq(String ownerUserId, String conversationId) {
        return PersistenceExceptions.runRedis("get conversation read sequence", () -> {
            String val = async.hget(dataKey(ownerUserId, conversationId), "readSeq")
                    .get(REDIS_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            return val != null ? Long.parseLong(val) : 0;
        });
    }

    @Override
    public int getTotalUnreadCount(String userId) {
        return PersistenceExceptions.runRedis("get total unread count", () -> {
            List<String> convIds = async.zrange(listKey(userId), 0, -1)
                    .get(REDIS_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (convIds == null || convIds.isEmpty()) return 0;

            int total = 0;
            for (String convId : convIds) {
                total += getUnreadCount(userId, convId);
            }
            return total;
        });
    }

    @Override
    public int getUnreadCount(String ownerUserId, String conversationId) {
        return PersistenceExceptions.runRedis("get unread count", () -> {
            String val = async.hget(dataKey(ownerUserId, conversationId), "unreadCount")
                    .get(REDIS_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            return val != null ? Integer.parseInt(val) : 0;
        });
    }

    // ========== 增量同步 ==========

    @Override
    public IncrementalSyncResult<Conversation> getIncrementalConversations(String ownerUserId, long version) {
        return PersistenceExceptions.runRedis("get incremental conversations", () -> {
            String verStr = async.get(versionKey(ownerUserId))
                    .get(REDIS_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            long currentVersion = verStr != null ? Long.parseLong(verStr) : 0;
            if (currentVersion <= version) {
                return IncrementalSyncResult.empty(currentVersion);
            }

            List<Conversation> all = getConversations(ownerUserId);
            return new IncrementalSyncResult<>(all, currentVersion, false);
        });
    }

    // ========== 工具方法 ==========

    private void incrVersion(String ownerUserId) {
        PersistenceExceptions.runRedis("increment conversation sync version", () ->
                async.incr(versionKey(ownerUserId)).get(REDIS_TIMEOUT_MS, TimeUnit.MILLISECONDS));
    }

    private Conversation hashToConversation(Map<String, String> fields) {
        Conversation conv = new Conversation();
        conv.setOwnerUserId(fields.get("ownerUserId"));
        conv.setConversationId(fields.get("conversationId"));
        conv.setSessionType(intField(fields, "sessionType", Conversation.SESSION_TYPE_SINGLE));
        conv.setUserId(fields.get("userId"));
        conv.setGroupId(fields.get("groupId"));
        conv.setUnreadCount(longField(fields, "unreadCount", 0));
        conv.setLastMsgContent(fields.get("lastMsgContent"));
        conv.setLastContentType(intField(fields, "lastContentType", 0));
        conv.setLastMsgId(fields.get("lastMsgId"));
        conv.setLastMsgSeq(longField(fields, "lastMsgSeq", 0));
        conv.setLastMsgTime(longField(fields, "lastMsgTime", 0));
        conv.setPinned("1".equals(fields.get("isPinned")));
        conv.setRecvMsgOpt(intField(fields, "recvMsgOpt", 0));
        conv.setGroupAtType(intField(fields, "groupAtType", 0));
        conv.setBurnDuration(intField(fields, "burnDuration", 0));
        conv.setMsgDestruct("1".equals(fields.get("isMsgDestruct")));
        conv.setMsgDestructTime(intField(fields, "msgDestructTime", 0));
        conv.setPrivateChat("1".equals(fields.get("isPrivateChat")));
        conv.setAttachedInfo(fields.get("attachedInfo"));
        conv.setEx(fields.get("ex"));
        conv.setCreateTime(longField(fields, "createTime", 0));
        conv.setUpdateTime(longField(fields, "updateTime", 0));
        return conv;
    }

    private static Map<String, String> conversationToHash(Conversation conv) {
        Map<String, String> map = new LinkedHashMap<>();
        putIfNotNull(map, "ownerUserId", conv.getOwnerUserId());
        putIfNotNull(map, "conversationId", conv.getConversationId());
        map.put("sessionType", String.valueOf(conv.getSessionType()));
        putIfNotNull(map, "userId", conv.getUserId());
        putIfNotNull(map, "groupId", conv.getGroupId());
        map.put("unreadCount", String.valueOf(conv.getUnreadCount()));
        putIfNotNull(map, "lastMsgContent", conv.getLastMsgContent());
        map.put("lastContentType", String.valueOf(conv.getLastContentType()));
        putIfNotNull(map, "lastMsgId", conv.getLastMsgId());
        map.put("lastMsgSeq", String.valueOf(conv.getLastMsgSeq()));
        map.put("lastMsgTime", String.valueOf(conv.getLastMsgTime()));
        map.put("isPinned", conv.isPinned() ? "1" : "0");
        map.put("recvMsgOpt", String.valueOf(conv.getRecvMsgOpt()));
        map.put("groupAtType", String.valueOf(conv.getGroupAtType()));
        map.put("burnDuration", String.valueOf(conv.getBurnDuration()));
        map.put("isMsgDestruct", conv.isMsgDestruct() ? "1" : "0");
        map.put("msgDestructTime", String.valueOf(conv.getMsgDestructTime()));
        map.put("isPrivateChat", conv.isPrivateChat() ? "1" : "0");
        putIfNotNull(map, "attachedInfo", conv.getAttachedInfo());
        putIfNotNull(map, "ex", conv.getEx());
        map.put("createTime", String.valueOf(conv.getCreateTime()));
        map.put("updateTime", String.valueOf(conv.getUpdateTime()));
        return map;
    }

    private static void putIfNotNull(Map<String, String> map, String key, String value) {
        if (value != null) map.put(key, value);
    }

    private static int intField(Map<String, String> fields, String key, int defaultValue) {
        String v = fields.get(key);
        return v != null ? Integer.parseInt(v) : defaultValue;
    }

    private static long longField(Map<String, String> fields, String key, long defaultValue) {
        String v = fields.get(key);
        return v != null ? Long.parseLong(v) : defaultValue;
    }

    private static String extractContentPreview(Message msg) {
        byte[] body = msg.getBody();
        if (body == null || body.length == 0) return null;
        String raw = new String(body, StandardCharsets.UTF_8);
        if (raw.length() > 100) return raw.substring(0, 100) + "...";
        return raw;
    }
}
