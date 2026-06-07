package com.im.core.handler.unified;

import com.im.api.ApiRequest;
import com.im.api.ISystemMessageStore;
import com.im.api.IUserManager;
import com.im.api.Operation;
import com.im.api.SystemChannel;
import com.im.api.SystemMessage;
import com.im.api.SystemMessageInboxItem;
import com.im.api.SystemMessageSummary;
import com.im.api.UserAdminLevel;
import com.im.api.UserInformation;
import com.im.common.exception.ForbiddenException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SystemMessageHandlerTest {

    @Test
    void adminPublishPersistsInboxAndNotifiesTargetUsers() {
        RecordingStore store = new RecordingStore();
        RecordingNotifier notifier = new RecordingNotifier();
        SystemMessageHandler handler = new SystemMessageHandler(store, new StaticUserManager(UserAdminLevel.ADMIN), notifier);
        ApiRequest request = request(Operation.ADMIN_SYSTEM_MESSAGE_PUBLISH, Map.of(
                "channelId", "wallet",
                "title", "余额变动",
                "summary", "收到一笔入账",
                "content", "你的账户收到一笔入账",
                "targetUserIds", List.of("u1", "u2", "u1")));
        request.setAttribute(ApiRequest.ATTR_USER_ID, "admin");

        @SuppressWarnings("unchecked")
        Map<String, Object> response = (Map<String, Object>) handler.handle(request);

        assertEquals("OK", response.get("status"));
        assertEquals("wallet", store.savedMessage.getChannelId());
        assertEquals(List.of("u1", "u2"), store.inboxUserIds);
        assertEquals(List.of("u1", "u2"), notifier.userIds);
        assertEquals("余额变动", notifier.summary.getTitle());
    }

    @Test
    void normalUserCannotPublishSystemMessage() {
        SystemMessageHandler handler = new SystemMessageHandler(
                new RecordingStore(), new StaticUserManager(UserAdminLevel.NORMAL), (userIds, summary) -> { });
        ApiRequest request = request(Operation.ADMIN_SYSTEM_MESSAGE_PUBLISH, Map.of(
                "channelId", "wallet",
                "title", "余额变动",
                "content", "你的账户收到一笔入账",
                "targetUserIds", List.of("u1")));
        request.setAttribute(ApiRequest.ATTR_USER_ID, "u1");

        assertThrows(ForbiddenException.class, () -> handler.handle(request));
    }

    @Test
    void userInboxOperationsUseAuthenticatedUserId() {
        RecordingStore store = new RecordingStore();
        store.inboxItems = List.of(item("m1"));
        SystemMessageHandler handler = new SystemMessageHandler(store, new StaticUserManager(UserAdminLevel.NORMAL), (userIds, summary) -> { });

        ApiRequest list = request(Operation.SYSTEM_MESSAGE_LIST, Map.of("channelId", "wallet", "onlyUnread", true, "limit", 10));
        list.setAttribute(ApiRequest.ATTR_USER_ID, "u1");
        @SuppressWarnings("unchecked")
        Map<String, Object> listResponse = (Map<String, Object>) handler.handle(list);

        ApiRequest read = request(Operation.SYSTEM_MESSAGE_READ, Map.of("messageId", "m1"));
        read.setAttribute(ApiRequest.ATTR_USER_ID, "u1");
        handler.handle(read);

        ApiRequest unread = request(Operation.SYSTEM_MESSAGE_UNREAD_COUNT, Map.of());
        unread.setAttribute(ApiRequest.ATTR_USER_ID, "u1");
        @SuppressWarnings("unchecked")
        Map<String, Object> unreadResponse = (Map<String, Object>) handler.handle(unread);

        assertEquals(List.of(item("m1")).get(0).getMessageId(),
                ((List<?>) listResponse.get("messages")).stream().map(SystemMessageInboxItem.class::cast).findFirst().orElseThrow().getMessageId());
        assertEquals("u1", store.lastListUserId);
        assertEquals("wallet", store.lastListChannelId);
        assertEquals(true, store.lastOnlyUnread);
        assertEquals("u1", store.lastReadUserId);
        assertEquals("m1", store.lastReadMessageId);
        assertEquals(3, unreadResponse.get("count"));
        assertEquals(Map.of("wallet", 3), unreadResponse.get("byChannel"));
    }

    private static ApiRequest request(Operation operation, Map<String, Object> params) {
        return new ApiRequest(operation, params, Map.of(), null, null);
    }

    private static SystemMessageInboxItem item(String messageId) {
        SystemMessageInboxItem item = new SystemMessageInboxItem();
        item.setMessageId(messageId);
        item.setTitle("title");
        return item;
    }

    private static final class RecordingStore implements ISystemMessageStore {
        private SystemMessage savedMessage;
        private final List<String> inboxUserIds = new ArrayList<>();
        private List<SystemMessageInboxItem> inboxItems = List.of();
        private String lastListUserId;
        private String lastListChannelId;
        private boolean lastOnlyUnread;
        private String lastReadUserId;
        private String lastReadMessageId;

        @Override public List<SystemChannel> listChannels() { return List.of(); }
        @Override public void ensureChannel(SystemChannel channel) {}
        @Override public void saveMessage(SystemMessage message) { savedMessage = message; }
        @Override public void addInbox(String messageId, String userId, String channelId, long createdAt) { inboxUserIds.add(userId); }
        @Override
        public List<SystemMessageInboxItem> listInbox(String userId, String channelId, boolean onlyUnread, int limit, long cursor) {
            lastListUserId = userId;
            lastListChannelId = channelId;
            lastOnlyUnread = onlyUnread;
            return inboxItems;
        }
        @Override public SystemMessageInboxItem getInboxMessage(String userId, String messageId) { return item(messageId); }
        @Override public void markRead(String userId, String messageId, long readAt) { lastReadUserId = userId; lastReadMessageId = messageId; }
        @Override public int markAllRead(String userId, String channelId, long readAt) { return 0; }
        @Override public int unreadCount(String userId, String channelId) { return 3; }
        @Override public Map<String, Integer> unreadCountByChannel(String userId) { return new LinkedHashMap<>(Map.of("wallet", 3)); }
    }

    private static final class RecordingNotifier implements com.im.api.SystemMessageNotifier {
        private List<String> userIds = List.of();
        private SystemMessageSummary summary;

        @Override
        public void notify(List<String> userIds, SystemMessageSummary summary) {
            this.userIds = userIds;
            this.summary = summary;
        }
    }

    private static final class StaticUserManager implements IUserManager {
        private final UserAdminLevel level;

        private StaticUserManager(UserAdminLevel level) {
            this.level = level;
        }

        @Override public void register(String userId, String nickname, String faceUrl, String ex) {}
        @Override
        public UserInformation getUserInformation(String userId) {
            UserInformation user = new UserInformation(userId, userId);
            user.setAppMangerLevel(level);
            return user;
        }
        @Override public List<UserInformation> getUsersInfo(List<String> userIds) { return List.of(); }
        @Override public Map<String, List<Integer>> getOnlineStatus(List<String> userIds) { return Map.of(); }
        @Override public void updateUserInformation(String userId, String nickname, String faceUrl, String ex, int globalRecvMsgOpt) {}
        @Override public List<UserInformation> searchUsers(String keyword, int limit) { return List.of(); }
    }
}
