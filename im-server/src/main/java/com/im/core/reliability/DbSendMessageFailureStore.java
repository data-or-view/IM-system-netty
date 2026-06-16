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
    public List<MessageSendFailureRecord> findDueFailures(long nowMillis, int limit) {
        try (SqlSession session = MyBatisPlusFactory.openSession()) {
            MessageSendFailureMapper mapper = session.getMapper(MessageSendFailureMapper.class);
            List<MessageSendFailureEntity> entities = mapper.selectList(new QueryWrapper<MessageSendFailureEntity>()
                    .eq("status", "PENDING")
                    .le("next_retry_at", nowMillis)
                    .orderByAsc("next_retry_at")
                    .last("LIMIT " + Math.max(1, limit)));
            return entities.stream()
                    .map(e -> new MessageSendFailureRecord(
                            e.getId(),
                            e.getTopic(),
                            e.getMessageId(),
                            e.getPayloadJson(),
                            e.getAttemptCount() == null ? 0 : e.getAttemptCount()))
                    .toList();
        } catch (Exception e) {
            throw new DatabasePersistenceException("failed to find due message send failures", e);
        }
    }

    @Override
    public void markReplayed(long id) {
        updateStatus(id, "SUCCEEDED", null, null, null);
    }

    @Override
    public void markRetryLater(long id, int attemptCount, long nextRetryAt, Throwable cause) {
        updateStatus(id, "PENDING", attemptCount, nextRetryAt, cause);
    }

    @Override
    public void markFailed(long id, int attemptCount, Throwable cause) {
        updateStatus(id, "FAILED", attemptCount, null, cause);
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
        entity.setStatus("PENDING");
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
        update.setStatus("PENDING");
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
