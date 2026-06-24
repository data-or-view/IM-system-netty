package com.im.core.reliability;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.im.api.Message;
import com.im.api.MessageSendFailureRecord;
import com.im.api.SendMessageFailureStore;
import com.im.common.exception.DatabasePersistenceException;
import com.im.core.db.MyBatisPlusFactory;
import com.im.core.db.entity.MessageSendFailureEntity;
import com.im.core.db.mapper.MessageSendFailureMapper;
import org.apache.ibatis.session.SqlSession;

import java.util.List;

public final class DbSendMessageFailureStore implements SendMessageFailureStore {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final int MAX_ERROR_LENGTH = 2000;
    static final String STATUS_PENDING = "PENDING";
    static final String STATUS_RETRYING = "RETRYING";
    static final String STATUS_REPUBLISHED = "REPUBLISHED";
    static final String STATUS_FAILED = "FAILED";

    @Override
    public void recordFailure(String topic, Message message, Throwable cause) {
        try (SqlSession session = MyBatisPlusFactory.openSession()) {
            MessageSendFailureMapper mapper = session.getMapper(MessageSendFailureMapper.class);
            MessageSendFailureEntity existing = mapper.selectOne(new QueryWrapper<MessageSendFailureEntity>()
                    .eq("topic", topic)
                    .eq("message_id", nullToEmpty(message.getMessageId()))
                    .last("LIMIT 1"));
            if (existing == null) {
                mapper.insert(toEntity(topic, message, cause));
            } else {
                updateExistingFailure(mapper, existing.getId(), message, cause);
            }
            session.commit();
        } catch (Exception e) {
            throw new DatabasePersistenceException("failed to record message send failure", e);
        }
    }

    @Override
    public List<MessageSendFailureRecord> claimDueFailures(long nowMillis, int limit) {
        return claimDueFailures(nowMillis, limit, 30_000L);
    }

    @Override
    public List<MessageSendFailureRecord> claimDueFailures(long nowMillis, int limit, long leaseMillis) {
        try (SqlSession session = MyBatisPlusFactory.openSession()) {
            MessageSendFailureMapper mapper = session.getMapper(MessageSendFailureMapper.class);
            QuerySpec querySpec = dueClaimQuery(nowMillis, limit);
            List<MessageSendFailureRecord> claimed = mapper.selectList(querySpec.wrapper())
                    .stream()
                    .filter(entity -> claimOne(mapper, entity.getId(), nowMillis, nowMillis + Math.max(1, leaseMillis)))
                    .map(DbSendMessageFailureStore::toRecord)
                    .toList();
            session.commit();
            return claimed;
        } catch (Exception e) {
            throw new DatabasePersistenceException("failed to claim due message send failures", e);
        }
    }

    @Override
    public List<MessageSendFailureRecord> findDueFailures(long nowMillis, int limit) {
        return claimDueFailures(nowMillis, limit);
    }

    @Override
    public void markRepublished(long id) {
        updateStatus(id, STATUS_REPUBLISHED, null, null, null);
    }

    @Override
    public void markRetryLater(long id, int attemptCount, long nextRetryAt, Throwable cause) {
        updateStatus(id, STATUS_PENDING, attemptCount, nextRetryAt, cause);
    }

    @Override
    public void markFailed(long id, int attemptCount, Throwable cause) {
        updateStatus(id, STATUS_FAILED, attemptCount, null, cause);
    }

    static boolean claimOne(MessageSendFailureMapper mapper, long id, long nowMillis, long leaseUntilMillis) {
        MessageSendFailureEntity update = new MessageSendFailureEntity();
        update.setStatus(STATUS_RETRYING);
        update.setUpdatedAt(nowMillis);
        update.setNextRetryAt(leaseUntilMillis);
        return mapper.update(update, new UpdateWrapper<MessageSendFailureEntity>()
                .eq("id", id)
                .and(w -> w.eq("status", STATUS_PENDING)
                        .or(n -> n.eq("status", STATUS_RETRYING).le("next_retry_at", nowMillis)))) == 1;
    }

    static QuerySpec dueClaimQueryForTest(long nowMillis, int limit) {
        return dueClaimQuery(nowMillis, limit);
    }

    private static QuerySpec dueClaimQuery(long nowMillis, int limit) {
        QueryWrapper<MessageSendFailureEntity> wrapper = new QueryWrapper<MessageSendFailureEntity>()
                .and(w -> w.eq("status", STATUS_PENDING)
                        .or(n -> n.eq("status", STATUS_RETRYING).le("next_retry_at", nowMillis)))
                .orderByAsc("next_retry_at")
                .last("LIMIT " + Math.max(1, limit));
        return new QuerySpec(wrapper);
    }

    record QuerySpec(QueryWrapper<MessageSendFailureEntity> wrapper) {}

    private static MessageSendFailureRecord toRecord(MessageSendFailureEntity e) {
        return new MessageSendFailureRecord(
                e.getId(),
                e.getTopic(),
                e.getMessageId(),
                e.getPayloadJson(),
                e.getAttemptCount() == null ? 0 : e.getAttemptCount());
    }

    private static MessageSendFailureEntity toEntity(String topic, Message message, Throwable cause) throws Exception {
        long now = System.currentTimeMillis();
        MessageSendFailureEntity entity = new MessageSendFailureEntity();
        entity.setTopic(topic);
        entity.setMessageId(nullToEmpty(message.getMessageId()));
        entity.setClientMsgId(nullToEmpty(message.getMessageId()));
        entity.setConversationId(nullToEmpty(message.getConversationId()));
        entity.setFromUserId(nullToEmpty(message.getFromUserId()));
        entity.setToUserId(nullToEmpty(message.getToUserId()));
        entity.setGroupId(nullToEmpty(message.getGroupId()));
        entity.setPayloadJson(OBJECT_MAPPER.writeValueAsString(message.toJsonMap()));
        entity.setStatus(STATUS_PENDING);
        entity.setAttemptCount(0);
        entity.setNextRetryAt(now + 1000);
        entity.setLastError(truncate(errorMessage(cause), MAX_ERROR_LENGTH));
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        return entity;
    }

    private static void updateExistingFailure(MessageSendFailureMapper mapper, long id,
                                              Message message, Throwable cause) throws Exception {
        long now = System.currentTimeMillis();
        MessageSendFailureEntity update = new MessageSendFailureEntity();
        update.setPayloadJson(OBJECT_MAPPER.writeValueAsString(message.toJsonMap()));
        update.setStatus(STATUS_PENDING);
        update.setNextRetryAt(now + 1000);
        update.setLastError(truncate(errorMessage(cause), MAX_ERROR_LENGTH));
        update.setUpdatedAt(now);
        mapper.update(update, new UpdateWrapper<MessageSendFailureEntity>().eq("id", id));
    }

    private void updateStatus(long id, String status, Integer attemptCount, Long nextRetryAt, Throwable cause) {
        try (SqlSession session = MyBatisPlusFactory.openSession()) {
            MessageSendFailureMapper mapper = session.getMapper(MessageSendFailureMapper.class);
            MessageSendFailureEntity update = new MessageSendFailureEntity();
            update.setStatus(status);
            if (attemptCount != null) {
                update.setAttemptCount(attemptCount);
            }
            if (nextRetryAt != null) {
                update.setNextRetryAt(nextRetryAt);
            }
            if (cause != null) {
                update.setLastError(truncate(errorMessage(cause), MAX_ERROR_LENGTH));
            }
            update.setUpdatedAt(System.currentTimeMillis());
            mapper.update(update, new UpdateWrapper<MessageSendFailureEntity>().eq("id", id));
            session.commit();
        } catch (Exception e) {
            throw new DatabasePersistenceException("failed to update message send failure", e);
        }
    }

    private static String errorMessage(Throwable cause) {
        if (cause == null) {
            return "";
        }
        String message = cause.getMessage();
        return cause.getClass().getName() + (message == null || message.isBlank() ? "" : ": " + message);
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
