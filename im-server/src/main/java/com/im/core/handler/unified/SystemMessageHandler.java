package com.im.core.handler.unified;

import com.im.api.ApiRequest;
import com.im.api.ISystemMessageStore;
import com.im.api.IUserManager;
import com.im.api.RequestHandler;
import com.im.api.SystemMessage;
import com.im.api.SystemMessageNotifier;
import com.im.api.UserAdminLevel;
import com.im.api.UserInformation;
import com.im.common.exception.ForbiddenException;
import com.im.common.exception.NotFoundException;
import com.im.common.exception.UnauthorizedException;
import com.im.common.exception.ValidationException;
import com.im.core.system.SystemMessagePublishUseCase;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class SystemMessageHandler implements RequestHandler {

    private final ISystemMessageStore store;
    private final IUserManager userManager;
    private final SystemMessagePublishUseCase publishUseCase;

    public SystemMessageHandler(ISystemMessageStore store, IUserManager userManager, SystemMessageNotifier notifier) {
        this.store = store;
        this.userManager = userManager;
        this.publishUseCase = new SystemMessagePublishUseCase(store, notifier);
    }

    @Override
    public Object handle(ApiRequest req) {
        return switch (req.operation()) {
            case "system.channel.list" -> handleChannelList(req);
            case "system.message.list" -> handleMessageList(req);
            case "system.message.detail" -> handleMessageDetail(req);
            case "system.message.read" -> handleMessageRead(req);
            case "system.message.read_all" -> handleMessageReadAll(req);
            case "system.message.unread_count" -> handleUnreadCount(req);
            case "admin.system.message.publish" -> handlePublish(req);
            default -> throw new NotFoundException("unsupported: " + req.operation());
        };
    }

    private Object handleChannelList(ApiRequest req) {
        requireUser(req);
        var channels = store.listChannels();
        return Map.of("channels", channels, "count", channels.size());
    }

    private Object handleMessageList(ApiRequest req) {
        String userId = requireUser(req);
        String channelId = req.getString("channelId");
        boolean onlyUnread = req.getBoolean("onlyUnread", false);
        int limit = req.getInt("limit", 20);
        long cursor = req.getLong("cursor", 0);
        var messages = store.listInbox(userId, channelId, onlyUnread, limit, cursor);
        return Map.of("messages", messages, "count", messages.size());
    }

    private Object handleMessageDetail(ApiRequest req) {
        String userId = requireUser(req);
        String messageId = req.getString("messageId");
        if (messageId == null || messageId.isBlank()) {
            throw new ValidationException("messageId is required");
        }
        var message = store.getInboxMessage(userId, messageId);
        if (message == null) {
            throw new NotFoundException("system message not found");
        }
        return message;
    }

    private Object handleMessageRead(ApiRequest req) {
        String userId = requireUser(req);
        String messageId = req.getString("messageId");
        if (messageId == null || messageId.isBlank()) {
            throw new ValidationException("messageId is required");
        }
        store.markRead(userId, messageId, System.currentTimeMillis());
        return Map.of("status", "OK");
    }

    private Object handleMessageReadAll(ApiRequest req) {
        String userId = requireUser(req);
        int updated = store.markAllRead(userId, req.getString("channelId"), System.currentTimeMillis());
        return Map.of("status", "OK", "updated", updated);
    }

    private Object handleUnreadCount(ApiRequest req) {
        String userId = requireUser(req);
        String channelId = req.getString("channelId");
        if (channelId != null && !channelId.isBlank()) {
            return Map.of("count", store.unreadCount(userId, channelId));
        }
        Map<String, Integer> byChannel = store.unreadCountByChannel(userId);
        int total = byChannel.values().stream().mapToInt(Integer::intValue).sum();
        return Map.of("count", total, "byChannel", byChannel);
    }

    private Object handlePublish(ApiRequest req) {
        requireAdmin(req);
        SystemMessage message = new SystemMessage();
        message.setChannelId(req.getString("channelId"));
        message.setTitle(req.getString("title"));
        message.setSummary(req.getString("summary", ""));
        message.setContent(req.getString("content"));
        message.setContentType(req.getString("contentType", "text"));
        message.setPriority(req.getInt("priority", 0));
        message.setExpireAt(req.getLong("expireAt", 0));

        var summary = publishUseCase.publishToUsers(message, toStringList(req.param("targetUserIds")));
        return Map.of("status", "OK", "message", summary);
    }

    private String requireUser(ApiRequest req) {
        String userId = req.currentUserId();
        if (userId == null || userId.isBlank()) {
            throw new UnauthorizedException("not authenticated");
        }
        return userId;
    }

    private void requireAdmin(ApiRequest req) {
        String userId = requireUser(req);
        UserInformation user = userManager.getUserInformation(userId);
        UserAdminLevel level = user != null ? user.getAppMangerLevel() : UserAdminLevel.NORMAL;
        if (level.getCode() < UserAdminLevel.ADMIN.getCode()) {
            throw new ForbiddenException("admin permission required");
        }
    }

    private static List<String> toStringList(Object value) {
        if (!(value instanceof List<?> list)) {
            throw new ValidationException("targetUserIds is required");
        }
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            if (item != null && !item.toString().isBlank()) {
                result.add(item.toString());
            }
        }
        return result;
    }
}
