package com.im.core.conversation;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.im.api.Conversation;
import com.im.api.IConversationManager;
import com.im.api.Message;
import com.im.core.db.MyBatisPlusFactory;
import com.im.core.db.entity.ConversationEntity;
import com.im.core.db.entity.SeqUserEntity;
import com.im.core.db.mapper.ConversationMapper;
import com.im.core.db.mapper.SeqUserMapper;
import com.im.common.retry.RetryConfig;
import com.im.common.retry.RetryExecutor;
import com.im.common.retry.RetryStrategies;
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

    public DbConversationManager(RetryExecutor retryExecutor) {
        this.retryExecutor = retryExecutor;
    }

    @Override
    public List<Conversation> getConversations(String ownerUserId) {
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
    }

    @Override
    public Conversation getConversation(String ownerUserId, String conversationId) {
        try (SqlSession session = MyBatisPlusFactory.openSession()) {
            ConversationMapper mapper = session.getMapper(ConversationMapper.class);
            ConversationEntity entity = mapper.selectByUserAndConversation(ownerUserId, conversationId);
            if (entity == null) return null;
            return toConversation(session, entity);
        }
    }

    @Override
    public void updateOnMessage(String ownerUserId, String conversationId, Message msg, boolean isSelf) {
        retryExecutor.execute(CFG, () -> {
            try (SqlSession session = MyBatisPlusFactory.openSession()) {
                ConversationMapper mapper = session.getMapper(ConversationMapper.class);

                // 构造附加信息 JSON（存储 lastMsgId / lastMsgContent）
                String attachedInfo = buildAttachedInfo(msg);

                // 计算会话类型
                int convType = conversationId != null && conversationId.startsWith("group_")
                        ? Conversation.SESSION_TYPE_GROUP : Conversation.SESSION_TYPE_SINGLE;

                // 提取对方 userId
                String targetUserId = null;
                if (convType == Conversation.SESSION_TYPE_SINGLE) {
                    String from = msg.getFromUserId();
                    String to = msg.getToUserId();
                    targetUserId = from != null && from.equals(ownerUserId) ? to : from;
                }

                long now = System.currentTimeMillis();
                long newSeq = msg.getSequenceId();

                // 插入或更新
                mapper.upsertConversation(
                        ownerUserId, conversationId, convType,
                        targetUserId, msg.getGroupId(),
                        attachedInfo, newSeq, now
                );

                // 更新未读数：send 方的会话（isSelf=true）不加
                if (!isSelf) {
                    mapper.incrementUnread(ownerUserId, conversationId);
                }

                session.commit();
            }
            return null;
        });
    }

    @Override
    public void markRead(String ownerUserId, String conversationId, long readSeq) {
        retryExecutor.execute(CFG, () -> {
            try (SqlSession session = MyBatisPlusFactory.openSession()) {
                ConversationMapper mapper = session.getMapper(ConversationMapper.class);
                mapper.resetUnread(ownerUserId, conversationId);
                mapper.updateUpdatedAt(ownerUserId, conversationId, System.currentTimeMillis());
                session.commit();
            }
            return null;
        });
    }

    @Override
    public void setPinned(String ownerUserId, String conversationId, boolean pinned) {
        retryExecutor.execute(CFG, () -> {
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
        });
    }

    @Override
    public void setRecvMsgOpt(String ownerUserId, String conversationId, int recvMsgOpt) {
        retryExecutor.execute(CFG, () -> {
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
        });
    }

    @Override
    public void setBurnDuration(String ownerUserId, String conversationId, int burnDuration) {
        retryExecutor.execute(CFG, () -> {
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
        });
    }

    @Override
    public void createSingleConversation(String ownerUserId, String targetUserId, String conversationId) {
        retryExecutor.execute(CFG, () -> {
            try (SqlSession session = MyBatisPlusFactory.openSession()) {
                ConversationMapper mapper = session.getMapper(ConversationMapper.class);
                // 检查是否已存在
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
        });
    }

    @Override
    public void createGroupConversations(List<String> memberIds, String groupId, String conversationId) {
        if (memberIds == null || memberIds.isEmpty()) return;

        retryExecutor.execute(CFG, () -> {
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
        });
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

        // 计算未读数：当前 conv max_seq - 用户 read_seq
        conv.setUnreadCount(computeUnreadCount(session, e));

        return conv;
    }

    private long computeUnreadCount(SqlSession session, ConversationEntity e) {
        try {
            SeqUserMapper seqMapper = session.getMapper(SeqUserMapper.class);
            SeqUserEntity seqUser = seqMapper.selectByUserAndConversation(
                    e.getOwnerUserId(), e.getConversationId());
            if (seqUser == null) {
                // 没有 SeqUser 记录时，未读数 = max_seq - 0
                return e.getMaxSeq() > 0 ? 1 : 0;
            }
            long unread = e.getMaxSeq() - seqUser.getReadSeq();
            return Math.max(unread, 0);
        } catch (Exception ex) {
            log.warn("Failed to compute unread for conv {}: {}", e.getConversationId(), ex.getMessage());
            return 0;
        }
    }

    /**
     * 从 attached_info JSON 解析 lastMsgId, lastMsgContent, lastContentType。
     * 格式: {@code {"mid":"xxx","txt":"hello","ct":1}}
     */
    private void parseAttachedInfo(Conversation conv, String attachedInfo) {
        if (attachedInfo == null || attachedInfo.isEmpty()) return;
        try {
            // 简易 JSON 解析（不引入依赖）
            // 格式: {"mid":"xxx","txt":"hello","ct":1}
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

    /**
     * 构建 attached_info JSON 字符串。
     */
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

    /** 简易 JSON value 提取（不含嵌套） */
    private static String extractJsonValue(String json, String key) {
        String search = "\"" + key + "\":";
        int idx = json.indexOf(search);
        if (idx < 0) return null;
        idx += search.length();
        // 跳过空白
        while (idx < json.length() && json.charAt(idx) == ' ') idx++;
        if (idx >= json.length()) return null;
        char c = json.charAt(idx);
        if (c == '"') {
            // 字符串：查找关闭引号
            idx++;
            int end = json.indexOf('"', idx);
            return end > idx ? json.substring(idx, end) : json.substring(idx);
        } else {
            // 数字/布尔
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
