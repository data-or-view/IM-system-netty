package com.im.core.system;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.im.api.ISystemMessageStore;
import com.im.api.SystemChannel;
import com.im.api.SystemMessage;
import com.im.api.SystemMessageInboxItem;
import com.im.common.exception.PersistenceExceptions;
import com.im.core.db.MyBatisPlusFactory;
import com.im.core.db.entity.SystemChannelEntity;
import com.im.core.db.entity.SystemMessageEntity;
import com.im.core.db.mapper.SystemChannelMapper;
import com.im.core.db.mapper.SystemMessageInboxMapper;
import com.im.core.db.mapper.SystemMessageMapper;
import org.apache.ibatis.session.SqlSession;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class DbSystemMessageStore implements ISystemMessageStore {

    @Override
    public List<SystemChannel> listChannels() {
        return PersistenceExceptions.runDatabase("list system channels", () -> {
            try (SqlSession session = MyBatisPlusFactory.openSession()) {
                return session.getMapper(SystemChannelMapper.class)
                        .selectList(new LambdaQueryWrapper<SystemChannelEntity>()
                                .eq(SystemChannelEntity::getStatus, 1)
                                .orderByAsc(SystemChannelEntity::getId))
                        .stream()
                        .map(this::toChannel)
                        .toList();
            }
        });
    }

    @Override
    public void ensureChannel(SystemChannel channel) {
        PersistenceExceptions.runDatabase("ensure system channel", () -> {
            try (SqlSession session = MyBatisPlusFactory.openSession()) {
                session.getMapper(SystemChannelMapper.class).insertIfAbsent(toChannelEntity(channel));
                session.commit();
            }
            return null;
        });
    }

    @Override
    public void saveMessage(SystemMessage message) {
        PersistenceExceptions.runDatabase("save system message", () -> {
            try (SqlSession session = MyBatisPlusFactory.openSession()) {
                session.getMapper(SystemMessageMapper.class).insert(toMessageEntity(message));
                session.commit();
            }
            return null;
        });
    }

    @Override
    public void addInbox(String messageId, String userId, String channelId, long createdAt) {
        PersistenceExceptions.runDatabase("add system message inbox", () -> {
            try (SqlSession session = MyBatisPlusFactory.openSession()) {
                session.getMapper(SystemMessageInboxMapper.class)
                        .insertIgnore(messageId, userId, channelId, createdAt);
                session.commit();
            }
            return null;
        });
    }

    @Override
    public List<SystemMessageInboxItem> listInbox(String userId, String channelId, boolean onlyUnread, int limit, long cursor) {
        int actualLimit = limit <= 0 ? 20 : Math.min(limit, 100);
        return PersistenceExceptions.runDatabase("list system message inbox", () -> {
            try (SqlSession session = MyBatisPlusFactory.openSession()) {
                return session.getMapper(SystemMessageInboxMapper.class)
                        .selectInbox(userId, channelId, onlyUnread, actualLimit, cursor, System.currentTimeMillis());
            }
        });
    }

    @Override
    public SystemMessageInboxItem getInboxMessage(String userId, String messageId) {
        return PersistenceExceptions.runDatabase("get system message detail", () -> {
            try (SqlSession session = MyBatisPlusFactory.openSession()) {
                return session.getMapper(SystemMessageInboxMapper.class).selectDetail(userId, messageId);
            }
        });
    }

    @Override
    public void markRead(String userId, String messageId, long readAt) {
        PersistenceExceptions.runDatabase("mark system message read", () -> {
            try (SqlSession session = MyBatisPlusFactory.openSession()) {
                session.getMapper(SystemMessageInboxMapper.class).markRead(userId, messageId, readAt);
                session.commit();
            }
            return null;
        });
    }

    @Override
    public int markAllRead(String userId, String channelId, long readAt) {
        return PersistenceExceptions.runDatabase("mark all system messages read", () -> {
            try (SqlSession session = MyBatisPlusFactory.openSession()) {
                int updated = session.getMapper(SystemMessageInboxMapper.class).markAllRead(userId, channelId, readAt);
                session.commit();
                return updated;
            }
        });
    }

    @Override
    public int unreadCount(String userId, String channelId) {
        return PersistenceExceptions.runDatabase("count unread system messages", () -> {
            try (SqlSession session = MyBatisPlusFactory.openSession()) {
                return session.getMapper(SystemMessageInboxMapper.class).unreadCount(userId, channelId);
            }
        });
    }

    @Override
    public Map<String, Integer> unreadCountByChannel(String userId) {
        return PersistenceExceptions.runDatabase("count unread system messages by channel", () -> {
            try (SqlSession session = MyBatisPlusFactory.openSession()) {
                Map<String, Integer> result = new LinkedHashMap<>();
                for (Map<String, Object> row : session.getMapper(SystemMessageInboxMapper.class).unreadCountByChannel(userId)) {
                    Object channelId = row.get("channelId");
                    Object count = row.get("count");
                    if (channelId != null && count instanceof Number number) {
                        result.put(channelId.toString(), number.intValue());
                    }
                }
                return result;
            }
        });
    }

    private SystemChannel toChannel(SystemChannelEntity entity) {
        SystemChannel channel = new SystemChannel();
        channel.setChannelId(entity.getChannelId());
        channel.setChannelName(entity.getChannelName());
        channel.setChannelType(entity.getChannelType());
        channel.setDescription(entity.getDescription());
        channel.setStatus(entity.getStatus());
        channel.setCreatedAt(entity.getCreatedAt());
        channel.setUpdatedAt(entity.getUpdatedAt());
        return channel;
    }

    private SystemChannelEntity toChannelEntity(SystemChannel channel) {
        long now = channel.getUpdatedAt() > 0 ? channel.getUpdatedAt() : System.currentTimeMillis();
        SystemChannelEntity entity = new SystemChannelEntity();
        entity.setChannelId(channel.getChannelId());
        entity.setChannelName(channel.getChannelName());
        entity.setChannelType(channel.getChannelType() != null ? channel.getChannelType() : "system");
        entity.setDescription(channel.getDescription() != null ? channel.getDescription() : "");
        entity.setStatus(channel.getStatus());
        entity.setCreatedAt(channel.getCreatedAt() > 0 ? channel.getCreatedAt() : now);
        entity.setUpdatedAt(now);
        return entity;
    }

    private SystemMessageEntity toMessageEntity(SystemMessage message) {
        SystemMessageEntity entity = new SystemMessageEntity();
        entity.setMessageId(message.getMessageId());
        entity.setChannelId(message.getChannelId());
        entity.setTitle(message.getTitle());
        entity.setSummary(message.getSummary() != null ? message.getSummary() : "");
        entity.setContent(message.getContent());
        entity.setContentType(message.getContentType() != null ? message.getContentType() : "text");
        entity.setSenderType(message.getSenderType() != null ? message.getSenderType() : "system");
        entity.setSenderId(message.getSenderId() != null ? message.getSenderId() : "im-system");
        entity.setPriority(message.getPriority());
        entity.setSendScope("USER_LIST");
        entity.setCreatedAt(message.getCreatedAt());
        entity.setExpireAt(message.getExpireAt());
        return entity;
    }
}
