package com.im.core.conversation;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.im.api.Conversation;
import com.im.api.IConversationManager;
import com.im.api.IncrementalSyncResult;
import com.im.api.Message;
import com.im.core.db.MyBatisPlusFactory;
import com.im.core.db.entity.ConversationEntity;
import com.im.core.db.entity.SeqUserEntity;
import com.im.core.db.mapper.ConversationMapper;
import com.im.core.db.mapper.SeqUserMapper;
import com.im.core.sync.DbIncrementalSync;
import com.im.common.retry.RetryConfig;
import com.im.common.retry.RetryExecutor;
import com.im.common.retry.RetryStrategies;
import com.im.common.exception.PersistenceExceptions;
import org.apache.ibatis.session.SqlSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * MySQL 会话管理器（生产环境用）。
 *
 * <p>基于 MyBatis-Plus 操作 {@code im_conversations} 表。
 * 每个用户独立拥有会话视图，通过 {@code owner_user_id} 隔离。</p>
 *
 * <p>未读数通过 {@code im_seq_users.read_seq} 与 {@code max_seq} 差值计算。</p>
 */
public class DbConversationManager implements IConversationManager {

    private static final Logger log = LoggerFactory.getLogger(DbConversationManager.class);
    private static final RetryConfig CFG = RetryStrategies.DB_WRITE;

    private final RetryExecutor retryExecutor;
    private final DbIncrementalSync sync;

    public DbConversationManager(RetryExecutor retryExecutor) {
        this(retryExecutor, new DbIncrementalSync(retryExecutor));
    }

    public DbConversationManager(RetryExecutor retryExecutor, DbIncrementalSync sync) {
        this.retryExecutor = retryExecutor;
        this.sync = sync;
    }

    @Override
    public List<Conversation> getConversations(String ownerUserId) {
        return PersistenceExceptions.runDatabase("get conversations", () -> {
            try (SqlSession session = MyBatisPlusFactory.openSession()) {
                ConversationMapper mapper = session.getMapper(ConversationMapper.class);
                List<ConversationEntity> entities = mapper.selectByUserOrdered(ownerUserId);
                if (entities == null || entities.isEmpty()) {
                    return Collections.emptyList();
                }
                return entities.stream()
                        .map(e -> toConversation(session, e))
                        .collect(Collectors.toList());
            }
        });
    }

    @Override
    public Conversation getConversation(String ownerUserId, String conversationId) {
        return PersistenceExceptions.runDatabase("get conversation", () -> {
            try (SqlSession session = MyBatisPlusFactory.openSession()) {
                ConversationMapper mapper = session.getMapper(ConversationMapper.class);
                ConversationEntity entity = mapper.selectByUserAndConversation(ownerUserId, conversationId);
                if (entity == null) return null;
                return toConversation(session, entity);
            }
        });
    }

    @Override
    public void updateOnMessage(String ownerUserId, String conversationId, Message msg, boolean isSelf) {
        PersistenceExceptions.runDatabase("update conversation on message", () -> retryExecutor.execute(CFG, () -> {
            try (SqlSession session = MyBatisPlusFactory.openSession()) {
                ConversationMapper mapper = session.getMapper(ConversationMapper.class);

                String attachedInfo = buildAttachedInfo(msg);

                int convType = conversationId != null && conversationId.startsWith("group_")
                        ? Conversation.SESSION_TYPE_GROUP : Conversation.SESSION_TYPE_SINGLE;

                String targetUserId = null;
                if (convType == Conversation.SESSION_TYPE_SINGLE) {
                    String from = msg.getFromUserId();
                    String to = msg.getToUserId();
                    targetUserId = from != null && from.equals(ownerUserId) ? to : from;
                }

                long now = System.currentTimeMillis();
                long newSeq = msg.getSequenceId();

                mapper.upsertConversation(
                        ownerUserId, conversationId, convType,
                        targetUserId, msg.getGroupId(),
                        attachedInfo, newSeq, now
                );

                if (!isSelf) {
                    mapper.incrementUnread(ownerUserId, conversationId);
                }

                ensureSeqUser(session, ownerUserId, conversationId, newSeq);

                session.commit();
            }
            return null;
        }));

        sync.recordChange(ownerUserId, "conversation", conversationId, "update");
    }

    @Override
    public long getReadSeq(String ownerUserId, String conversationId) {
        return PersistenceExceptions.runDatabase("get conversation read sequence", () -> {
            try (SqlSession session = MyBatisPlusFactory.openSession()) {
                SeqUserMapper seqMapper = session.getMapper(SeqUserMapper.class);
                SeqUserEntity seqUser = seqMapper.selectByUserAndConversation(ownerUserId, conversationId);
                return seqUser != null ? seqUser.getReadSeq() : 0;
            }
        });
    }

    @Override
    public int getTotalUnreadCount(String userId) {
        return PersistenceExceptions.runDatabase("get total unread count", () -> {
            try (SqlSession session = MyBatisPlusFactory.openSession()) {
                ConversationMapper mapper = session.getMapper(ConversationMapper.class);
                List<ConversationEntity> entities = mapper.selectByUserOrdered(userId);
                if (entities == null || entities.isEmpty()) return 0;

                int total = 0;
                for (ConversationEntity e : entities) {
                    total += computeUnreadCount(session, e);
                }
                return total;
            }
        });
    }

    @Override
    public int getUnreadCount(String ownerUserId, String conversationId) {
        return PersistenceExceptions.runDatabase("get unread count", () -> {
            try (SqlSession session = MyBatisPlusFactory.openSession()) {
                ConversationMapper mapper = session.getMapper(ConversationMapper.class);
                ConversationEntity entity = mapper.selectByUserAndConversation(ownerUserId, conversationId);
                if (entity == null) return 0;
                return (int) computeUnreadCount(session, entity);
            }
        });
    }

    @Override
    public void markRead(String ownerUserId, String conversationId, long readSeq) {
        PersistenceExceptions.runDatabase("mark conversation read", () -> retryExecutor.execute(CFG, () -> {
            try (SqlSession session = MyBatisPlusFactory.openSession()) {
                ConversationMapper mapper = session.getMapper(ConversationMapper.class);
                mapper.resetUnread(ownerUserId, conversationId);
                mapper.updateUpdatedAt(ownerUserId, conversationId, System.currentTimeMillis());

                if (readSeq > 0) {
                    SeqUserMapper seqMapper = session.getMapper(SeqUserMapper.class);
                    seqMapper.updateReadSeq(ownerUserId, conversationId, readSeq, System.currentTimeMillis());
                }

                session.commit();
            }
            return null;
        }));

        sync.recordChange(ownerUserId, "conversation", conversationId, "update");
    }

    @Override
    public void setPinned(String ownerUserId, String conversationId, boolean pinned) {
        PersistenceExceptions.runDatabase("set conversation pinned", () -> retryExecutor.execute(CFG, () -> {
            try (SqlSession session = MyBatisPlusFactory.openSession()) {
                ConversationMapper mapper = session.getMapper(ConversationMapper.class);
                mapper.update(
                        null,
                        new LambdaUpdateWrapper<ConversationEntity>()
                                .eq(ConversationEntity::getOwnerUserId, ownerUserId)
                                .eq(ConversationEntity::getConversationId, conversationId)
                                .set(ConversationEntity::getIsPinned, pinned ? 1 : 0)
                                .set(ConversationEntity::getUpdatedAt, System.currentTimeMillis())
                );
                session.commit();
            }
            return null;
        }));

        sync.recordChange(ownerUserId, "conversation", conversationId, "update");
    }

    @Override
    public void setRecvMsgOpt(String ownerUserId, String conversationId, int recvMsgOpt) {
        PersistenceExceptions.runDatabase("set conversation receive option", () -> retryExecutor.execute(CFG, () -> {
            try (SqlSession session = MyBatisPlusFactory.openSession()) {
                ConversationMapper mapper = session.getMapper(ConversationMapper.class);
                mapper.update(
                        null,
                        new LambdaUpdateWrapper<ConversationEntity>()
                                .eq(ConversationEntity::getOwnerUserId, ownerUserId)
                                .eq(ConversationEntity::getConversationId, conversationId)
                                .set(ConversationEntity::getRecvMsgOpt, recvMsgOpt)
                                .set(ConversationEntity::getUpdatedAt, System.currentTimeMillis())
                );
                session.commit();
            }
            return null;
        }));

        sync.recordChange(ownerUserId, "conversation", conversationId, "update");
    }

    @Override
    public void setBurnDuration(String ownerUserId, String conversationId, int burnDuration) {
        PersistenceExceptions.runDatabase("set conversation burn duration", () -> retryExecutor.execute(CFG, () -> {
            try (SqlSession session = MyBatisPlusFactory.openSession()) {
                ConversationMapper mapper = session.getMapper(ConversationMapper.class);
                mapper.update(
                        null,
                        new LambdaUpdateWrapper<ConversationEntity>()
                                .eq(ConversationEntity::getOwnerUserId, ownerUserId)
                                .eq(ConversationEntity::getConversationId, conversationId)
                                .set(ConversationEntity::getBurnDuration, burnDuration)
                                .set(ConversationEntity::getUpdatedAt, System.currentTimeMillis())
                );
                session.commit();
            }
            return null;
        }));

        sync.recordChange(ownerUserId, "conversation", conversationId, "update");
    }

    @Override
    public void createSingleConversation(String ownerUserId, String targetUserId, String conversationId) {
        PersistenceExceptions.runDatabase("create single conversation", () -> retryExecutor.execute(CFG, () -> {
            try (SqlSession session = MyBatisPlusFactory.openSession()) {
                ConversationMapper mapper = session.getMapper(ConversationMapper.class);
                ConversationEntity existing = mapper.selectByUserAndConversation(ownerUserId, conversationId);
                if (existing != null) return null;

                ConversationEntity entity = new ConversationEntity();
                entity.setOwnerUserId(ownerUserId);
                entity.setConversationId(conversationId);
                entity.setConversationType(Conversation.SESSION_TYPE_SINGLE);
                entity.setUserId(targetUserId);
                entity.setCreatedAt(System.currentTimeMillis());
                entity.setUpdatedAt(System.currentTimeMillis());
                mapper.insert(entity);
                session.commit();
                log.debug("Single conversation created: owner={}, conv={}, target={}",
                        ownerUserId, conversationId, targetUserId);
            }
            return null;
        }));

        sync.recordChange(ownerUserId, "conversation", conversationId, "insert");
    }

    @Override
    public void createGroupConversations(List<String> memberIds, String groupId, String conversationId) {
        if (memberIds == null || memberIds.isEmpty()) return;

        PersistenceExceptions.runDatabase("create group conversations", () -> retryExecutor.execute(CFG, () -> {
            try (SqlSession session = MyBatisPlusFactory.openSession()) {
                ConversationMapper mapper = session.getMapper(ConversationMapper.class);
                long now = System.currentTimeMillis();

                for (String memberId : memberIds) {
                    ConversationEntity existing = mapper.selectByUserAndConversation(memberId, conversationId);
                    if (existing != null) continue;

                    ConversationEntity entity = new ConversationEntity();
                    entity.setOwnerUserId(memberId);
                    entity.setConversationId(conversationId);
                    entity.setConversationType(Conversation.SESSION_TYPE_GROUP);
                    entity.setGroupId(groupId);
                    entity.setCreatedAt(now);
                    entity.setUpdatedAt(now);
                    mapper.insert(entity);
                }
                session.commit();
                log.debug("Group conversations created: groupId={}, members={}", groupId, memberIds.size());
            }
            return null;
        }));

        for (String memberId : memberIds) {
            sync.recordChange(memberId, "conversation", conversationId, "insert");
        }
    }

    // ========== 增量同步 ==========

    @Override
    public IncrementalSyncResult<Conversation> getIncrementalConversations(String ownerUserId, long version) {
        return sync.getChanges(ownerUserId, "conversation", version,
                convId -> getConversation(ownerUserId, convId),
                convId -> null);
    }

    // ========== Entity ↔ Conversation 转换 ==========

    private Conversation toConversation(SqlSession session, ConversationEntity e) {
        Conversation conv = new Conversation();
        conv.setConversationId(e.getConversationId());
        conv.setOwnerUserId(e.getOwnerUserId());
        conv.setSessionType(e.getConversationType());
        conv.setUserId(e.getUserId());
        conv.setGroupId(e.getGroupId());
        conv.setRecvMsgOpt(e.getRecvMsgOpt());
        conv.setPinned(e.getIsPinned() == 1);
        conv.setPrivateChat(e.getIsPrivateChat() == 1);
        conv.setBurnDuration(e.getBurnDuration());
        conv.setGroupAtType(e.getGroupAtType());
        conv.setAttachedInfo(e.getAttachedInfo());
        conv.setEx(e.getEx());
        conv.setLastMsgSeq(e.getMaxSeq());
        conv.setLastMsgTime(e.getUpdatedAt());
        conv.setCreateTime(e.getCreatedAt());
        conv.setUpdateTime(e.getUpdatedAt());

        // 从 attached_info 解析 lastMsgId/lastMsgContent
        parseAttachedInfo(conv, e.getAttachedInfo());

        // 计算未读数
        conv.setUnreadCount(computeUnreadCount(session, e));

        return conv;
    }

    private void ensureSeqUser(SqlSession session, String userId, String conversationId, long newSeq) {
        SeqUserMapper seqMapper = session.getMapper(SeqUserMapper.class);
        SeqUserEntity existing = seqMapper.selectByUserAndConversation(userId, conversationId);
        if (existing == null) {
            SeqUserEntity su = new SeqUserEntity();
            su.setUserId(userId);
            su.setConversationId(conversationId);
            su.setMinSeq(newSeq);
            su.setMaxSeq(newSeq);
            su.setReadSeq(0);
            su.setUpdatedAt(System.currentTimeMillis());
            seqMapper.insert(su);
        } else if (newSeq > existing.getMaxSeq()) {
            existing.setMaxSeq(newSeq);
            existing.setUpdatedAt(System.currentTimeMillis());
            seqMapper.updateById(existing);
        }
    }

    private long computeUnreadCount(SqlSession session, ConversationEntity e) {
        SeqUserMapper seqMapper = session.getMapper(SeqUserMapper.class);
        SeqUserEntity seqUser = seqMapper.selectByUserAndConversation(
                e.getOwnerUserId(), e.getConversationId());
        if (seqUser == null) {
            return e.getMaxSeq() > 0 ? 1 : 0;
        }
        long unread = e.getMaxSeq() - seqUser.getReadSeq();
        return Math.max(unread, 0);
    }

    private void parseAttachedInfo(Conversation conv, String attachedInfo) {
        if (attachedInfo == null || attachedInfo.isEmpty()) return;
        try {
            String json = attachedInfo;
            conv.setLastMsgId(extractJsonValue(json, "mid"));
            conv.setLastMsgContent(extractJsonValue(json, "txt"));
            String ct = extractJsonValue(json, "ct");
            if (ct != null && !ct.isEmpty()) {
                conv.setLastContentType(Integer.parseInt(ct));
            }
        } catch (Exception e) {
            log.warn("Failed to parse attached_info for conv {}: {}",
                    conv.getConversationId(), e.getMessage());
        }
    }

    private String buildAttachedInfo(Message msg) {
        String mid = msg.getMessageId();
        String content = msg.getContent();
        String ct = msg.getContentType() > 0 ? String.valueOf(msg.getContentType()) : null;

        StringBuilder sb = new StringBuilder("{");
        if (mid != null && !mid.isEmpty()) {
            sb.append("\"mid\":\"").append(escapeJson(mid)).append("\"");
        }
        if (content != null && !content.isEmpty()) {
            if (sb.length() > 1) sb.append(",");
            String preview = content.length() > 100 ? content.substring(0, 100) + "..." : content;
            sb.append("\"txt\":\"").append(escapeJson(preview)).append("\"");
        }
        if (ct != null && !ct.isEmpty()) {
            if (sb.length() > 1) sb.append(",");
            sb.append("\"ct\":").append(ct);
        }
        sb.append("}");
        return sb.length() > 2 ? sb.toString() : "";
    }

    private static String extractJsonValue(String json, String key) {
        String search = "\"" + key + "\":";
        int idx = json.indexOf(search);
        if (idx < 0) return null;
        idx += search.length();
        while (idx < json.length() && json.charAt(idx) == ' ') idx++;
        if (idx >= json.length()) return null;
        char c = json.charAt(idx);
        if (c == '"') {
            idx++;
            int end = json.indexOf('"', idx);
            return end > idx ? json.substring(idx, end) : json.substring(idx);
        } else {
            int end = idx;
            while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) end++;
            return json.substring(idx, end);
        }
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
