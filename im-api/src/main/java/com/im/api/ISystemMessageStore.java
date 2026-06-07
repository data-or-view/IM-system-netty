package com.im.api;

import java.util.List;
import java.util.Map;

public interface ISystemMessageStore {

    List<SystemChannel> listChannels();

    void ensureChannel(SystemChannel channel);

    void saveMessage(SystemMessage message);

    void addInbox(String messageId, String userId, String channelId, long createdAt);

    List<SystemMessageInboxItem> listInbox(String userId, String channelId, boolean onlyUnread, int limit, long cursor);

    SystemMessageInboxItem getInboxMessage(String userId, String messageId);

    void markRead(String userId, String messageId, long readAt);

    int markAllRead(String userId, String channelId, long readAt);

    int unreadCount(String userId, String channelId);

    Map<String, Integer> unreadCountByChannel(String userId);
}
