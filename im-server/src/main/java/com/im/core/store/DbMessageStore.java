package com.im.core.store;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.im.api.ConversationIds;
import com.im.api.Message;
import com.im.api.SearchMessagesParam;
import com.im.api.SearchMessagesResult;
import com.im.api.IMessageStore;
import com.im.core.db.MyBatisPlusFactory;
import com.im.core.db.entity.MessageEntity;
import com.im.core.db.mapper.MessageMapper;
import com.im.common.retry.RetryConfig;
import com.im.common.retry.RetryExecutor;
import com.im.common.retry.RetryStrategies;
import com.im.api.content.ContentType;
import org.apache.ibatis.session.SqlSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * MySQL 消息持久化存储（生产环境用）。
 *
 * <p>基于 MyBatis-Plus 操作 {@code im_messages} 表。
 * 与 LocalMessageStore 接口兼容，替换后不影响业务代码流程。</p>
 *
 * <h3>离线消息策略</h3>
 * 不设独立的离线队列。每条消息保存时记录 sendId/recvId，
 * 拉取离线时通过 {@code WHERE recv_id = ? AND is_read = 0} 查询。
 * 投递确认后标记 {@code is_read = 1}。
 */
public class DbMessageStore implements IMessageStore {

    private static final Logger log = LoggerFactory.getLogger(DbMessageStore.class);
    private static final RetryConfig CFG = RetryStrategies.MESSAGE_STORE;

    private final RetryExecutor retryExecutor;

    public DbMessageStore(RetryExecutor retryExecutor) {
        this.retryExecutor = retryExecutor;
    }

    private static final int MAX_OFFLINE_PULL = 200;

    @Override
    public void save(Message msg) {
        MessageEntity entity = toEntity(msg);
        if (entity.getConversationId() == null) {
            log.warn("Cannot save message without conversationId: mid={}", msg.getMessageId());
            return;
        }
        retryExecutor.execute(CFG, () -> {
        try (SqlSession session = MyBatisPlusFactory.openSession()) {
            MessageMapper mapper = session.getMapper(MessageMapper.class);
            mapper.insert(entity);
            session.commit();
        }
                    return null;
        });
    }

    @Override
    public List<Message> pullBySequence(String conversationId, long startSeq, long endSeq, int limit) {
        int actualLimit = (limit <= 0) ? 50 : Math.min(limit, 200);

        try (SqlSession session = MyBatisPlusFactory.openSession()) {
            MessageMapper mapper = session.getMapper(MessageMapper.class);
            List<MessageEntity> entities;

            if (startSeq <= 0 && (endSeq <= 0 || endSeq >= Long.MAX_VALUE)) {
                // 拉取最近的 N 条
                entities = mapper.selectRecent(conversationId, actualLimit);
            } else {
                long from = Math.max(startSeq, 1);
                long to = (endSeq <= 0) ? Long.MAX_VALUE : endSeq;
                entities = mapper.selectBySeqRange(conversationId, from, to);
            }

            List<Message> result = new ArrayList<>(Math.min(entities.size(), actualLimit));
            for (int i = 0; i < Math.min(entities.size(), actualLimit); i++) {
                result.add(toMessage(entities.get(i)));
            }
            return result;
        }
    }

    @Override
    public List<Message> pullOffline(String userId, int limit) {
        if (userId == null || userId.isEmpty()) return Collections.emptyList();

        int actualLimit = Math.min(limit > 0 ? limit : 50, MAX_OFFLINE_PULL);

        try (SqlSession session = MyBatisPlusFactory.openSession()) {
            MessageMapper mapper = session.getMapper(MessageMapper.class);

            // 查询 recv_id = userId, is_read = 0 的消息作为离线消息
            List<MessageEntity> entities = mapper.selectList(
                    new LambdaQueryWrapper<MessageEntity>()
                            .eq(MessageEntity::getRecvId, userId)
                            .eq(MessageEntity::getIsRead, 0)
                            .orderByAsc(MessageEntity::getSeq)
                            .last("LIMIT " + actualLimit)
            );

            if (entities.isEmpty()) return Collections.emptyList();

            List<Message> result = new ArrayList<>(entities.size());
            for (MessageEntity entity : entities) {
                result.add(toMessage(entity));
            }
            return result;
        }
    }

    @Override
    public void markDelivered(String userId, List<String> msgIds) {
        if (userId == null || msgIds == null || msgIds.isEmpty()) return;

        retryExecutor.execute(CFG, () -> {
        try (SqlSession session = MyBatisPlusFactory.openSession()) {
            MessageMapper mapper = session.getMapper(MessageMapper.class);

            for (String clientMsgId : msgIds) {
                mapper.update(
                        null,
                        new LambdaUpdateWrapper<MessageEntity>()
                                .eq(MessageEntity::getClientMsgId, clientMsgId)
                                .eq(MessageEntity::getRecvId, userId)
                                .set(MessageEntity::getIsRead, 1)
                );
            }
            session.commit();
            log.debug("Marked {} messages delivered for user {}", msgIds.size(), userId);
        }
                    return null;
        });
    }

    @Override
    public void deleteBefore(String userId, long seqId) {
        retryExecutor.execute(CFG, () -> {
        try (SqlSession session = MyBatisPlusFactory.openSession()) {
            MessageMapper mapper = session.getMapper(MessageMapper.class);

            mapper.delete(
                    new LambdaQueryWrapper<MessageEntity>()
                            .eq(MessageEntity::getRecvId, userId)
                            .lt(MessageEntity::getSeq, seqId)
            );
            session.commit();
            log.debug("Deleted messages before seq {} for user {}", seqId, userId);
        }
                    return null;
        });
    }

    @Override
    public boolean revokeMessage(String conversationId, long seq, String revokerId, int role, String nickname) {
        return retryExecutor.execute(CFG, () -> {
            try (SqlSession session = MyBatisPlusFactory.openSession()) {
                MessageMapper mapper = session.getMapper(MessageMapper.class);
                int updated = mapper.revokeMessage(conversationId, seq, revokerId, role, nickname, System.currentTimeMillis());
                session.commit();
                return updated > 0;
            }
        });
    }

    @Override
    public SearchMessagesResult searchMessages(SearchMessagesParam param) {
        if (param == null || param.getUserId() == null) {
            return SearchMessagesResult.empty();
        }

        // 解析 contentTypeFilter: String → int IDs
        List<Integer> contentTypeIds = null;
        if (param.getContentTypeFilter() != null && !param.getContentTypeFilter().isEmpty()) {
            contentTypeIds = new ArrayList<>(param.getContentTypeFilter().size());
            for (String typeName : param.getContentTypeFilter()) {
                try {
                    ContentType ct = ContentType.valueOf(typeName.toUpperCase());
                    contentTypeIds.add(ct.getId());
                } catch (IllegalArgumentException e) {
                    // 跳过未知类型
                }
            }
        }

        try (SqlSession session = MyBatisPlusFactory.openSession()) {
            MessageMapper mapper = session.getMapper(MessageMapper.class);

            long total = mapper.countByKeyword(
                    param.getConversationIds(), param.getKeyword(),
                    contentTypeIds, param.getSenderId(),
                    param.getStartTime(), param.getEndTime());

            int limit = param.getLimit();
            int offset = param.getOffset();
            // 多查一行判断 hasMore
            List<MessageEntity> entities = mapper.selectByKeyword(
                    param.getConversationIds(), param.getKeyword(),
                    contentTypeIds, param.getSenderId(),
                    param.getStartTime(), param.getEndTime(),
                    limit + 1, offset);

            boolean hasMore = entities.size() > limit;
            if (hasMore) {
                entities = entities.subList(0, limit);
            }

            List<Message> messages = new ArrayList<>(entities.size());
            for (MessageEntity e : entities) {
                messages.add(toMessage(e));
            }

            return new SearchMessagesResult(messages, (int) total, hasMore);
        }
    }

    // ========== Entity / Message 互转 ==========

    private static MessageEntity toEntity(Message msg) {
        MessageEntity e = new MessageEntity();

        e.setClientMsgId(msg.getMessageId());
        String serverMsgId = msg.getMeta("serverMsgId");
        e.setServerMsgId(serverMsgId != null ? serverMsgId : msg.getMessageId());

        // conversationId
        String convId = msg.getConversationId();
        if (convId == null) {
            convId = ConversationIds.fromMessageParties(
                    msg.getFromUserId(), msg.getToUserId(), msg.getGroupId());
        }
        e.setConversationId(convId);

        e.setSendId(msg.getFromUserId());
        e.setRecvId(msg.getToUserId());
        e.setGroupId(msg.getGroupId());

        e.setContentType(msg.getContentType());
        e.setContent(msg.getContent());

        e.setSenderNickname(msg.getMeta("senderNickname"));
        e.setSenderFaceUrl(msg.getMeta("senderFaceUrl"));
        e.setSenderPlatformId(parseInt(msg.getMeta("platformId"), 0));

        e.setSeq(msg.getMessageSeq());
        e.setStatus(msg.getStatus());
        e.setIsRead(0);

        e.setSentAt(msg.getTimestamp());
        e.setCreatedAt(System.currentTimeMillis());

        return e;
    }

    private static Message toMessage(MessageEntity e) {
        Message msg = new Message();
        msg.setMessageId(e.getClientMsgId());
        if (e.getServerMsgId() != null) msg.putMeta("serverMsgId", e.getServerMsgId());
        msg.setConversationId(e.getConversationId());
        msg.setFromUserId(e.getSendId() != null ? e.getSendId() : "");
        msg.setToUserId(e.getRecvId() != null ? e.getRecvId() : "");
        msg.setGroupId(e.getGroupId());
        msg.setContentType(Math.max(e.getContentType(), 0));
        if (e.getContent() != null) msg.setContent(e.getContent());
        msg.setMessageSeq(e.getSeq());
        msg.setTimestamp(e.getSentAt());
        msg.setStatus(e.getStatus());
        if (e.getSenderNickname() != null) msg.putMeta("senderNickname", e.getSenderNickname());
        if (e.getSenderFaceUrl() != null) msg.putMeta("senderFaceUrl", e.getSenderFaceUrl());
        if (e.getAtUserIds() != null) msg.putMeta("atUserIds", e.getAtUserIds());
        return msg;
    }

    private static int parseInt(String s, int def) {
        if (s == null || s.isEmpty()) return def;
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return def; }
    }

    private static long parseLong(String s, long def) {
        if (s == null || s.isEmpty()) return def;
        try { return Long.parseLong(s); } catch (NumberFormatException e) { return def; }
    }
}
