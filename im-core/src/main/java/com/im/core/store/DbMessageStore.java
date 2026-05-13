package com.im.core.store;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.im.api.IMCommand;
import com.im.api.IMessageStore;
import com.im.core.db.MyBatisPlusFactory;
import com.im.core.db.entity.MessageEntity;
import com.im.core.db.mapper.MessageMapper;
import com.im.core.retry.RetryConfig;
import com.im.core.retry.RetryExecutor;
import com.im.core.retry.RetryStrategies;
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
    public void save(IMCommand msg) {
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
    public List<IMCommand> pullBySequence(String conversationId, long startSeq, long endSeq, int limit) {
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

            List<IMCommand> result = new ArrayList<>(Math.min(entities.size(), actualLimit));
            for (int i = 0; i < Math.min(entities.size(), actualLimit); i++) {
                result.add(toCommand(entities.get(i)));
            }
            return result;
        }
    }

    @Override
    public List<IMCommand> pullOffline(String userId, int limit) {
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

            List<IMCommand> result = new ArrayList<>(entities.size());
            for (MessageEntity entity : entities) {
                result.add(toCommand(entity));
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

    // ========== Entity / IMCommand 互转 ==========

    private static MessageEntity toEntity(IMCommand msg) {
        MessageEntity e = new MessageEntity();

        e.setClientMsgId(msg.getMessageId());
        e.setServerMsgId(msg.getHeader("serverMsgId") != null ?
                msg.getHeader("serverMsgId") : msg.getMessageId());

        // conversationId
        String convId = msg.getHeader("conversationId");
        if (convId == null) {
            convId = buildConversationId(
                    msg.getHeader("fromUserId"),
                    msg.getHeader("toUserId"),
                    msg.getHeader("groupId")
            );
        }
        e.setConversationId(convId);

        e.setSendId(msg.getHeader("fromUserId"));
        e.setRecvId(msg.getHeader("toUserId"));
        e.setGroupId(msg.getHeader("groupId"));

        e.setContentType(parseInt(msg.getHeader("contentType"), 0));
        e.setContent(msg.getHeader("content"));

        e.setSenderNickname(msg.getHeader("senderNickname"));
        e.setSenderFaceUrl(msg.getHeader("senderFaceUrl"));
        e.setSenderPlatformId(parseInt(msg.getHeader("platformId"), 0));

        e.setSeq(parseLong(msg.getHeader("_ms"), 0));
        e.setStatus(parseInt(msg.getHeader("_st"), 0));
        e.setIsRead(0);

        // 时间戳
        String ts = msg.getHeader("_ts");
        long now = System.currentTimeMillis();
        e.setSentAt(ts != null ? parseLong(ts, now) : now);
        e.setCreatedAt(now);

        return e;
    }

    private static IMCommand toCommand(MessageEntity e) {
        IMCommand cmd = new IMCommand();

        cmd.putHeader("_mid", e.getClientMsgId());
        if (e.getServerMsgId() != null) cmd.putHeader("serverMsgId", e.getServerMsgId());
        cmd.putHeader("conversationId", e.getConversationId());
        cmd.putHeader("fromUserId", e.getSendId() != null ? e.getSendId() : "");
        cmd.putHeader("toUserId", e.getRecvId() != null ? e.getRecvId() : "");
        if (e.getGroupId() != null) cmd.putHeader("groupId", e.getGroupId());
        if (e.getContentType() > 0) cmd.putHeader("contentType", String.valueOf(e.getContentType()));
        if (e.getContent() != null) cmd.putHeader("content", e.getContent());
        cmd.putHeader("_ms", String.valueOf(e.getSeq()));
        cmd.putHeader("_ts", String.valueOf(e.getSentAt()));
        cmd.putHeader("_st", String.valueOf(e.getStatus()));
        if (e.getSenderNickname() != null) cmd.putHeader("senderNickname", e.getSenderNickname());
        if (e.getSenderFaceUrl() != null) cmd.putHeader("senderFaceUrl", e.getSenderFaceUrl());
        if (e.getAtUserIds() != null) cmd.putHeader("atUserIds", e.getAtUserIds());

        return cmd;
    }

    private static String buildConversationId(String fromUserId, String toUserId, String groupId) {
        if (groupId != null) return "group_" + groupId;
        if (fromUserId != null && toUserId != null) {
            String u1 = fromUserId.compareTo(toUserId) <= 0 ? fromUserId : toUserId;
            String u2 = fromUserId.compareTo(toUserId) <= 0 ? toUserId : fromUserId;
            return "single_" + u1 + "_" + u2;
        }
        return null;
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
