package com.im.core.system;

import com.im.api.ISystemMessageStore;
import com.im.api.SystemChannel;
import com.im.api.SystemMessage;
import com.im.api.SystemMessageNotifier;
import com.im.api.SystemMessageSummary;
import com.im.common.exception.ValidationException;
import com.im.common.id.IdGenerator;
import com.im.common.validation.Preconditions;

import java.util.LinkedHashSet;
import java.util.List;

public class SystemMessagePublishUseCase {

    private final ISystemMessageStore store;
    private final SystemMessageNotifier notifier;

    public SystemMessagePublishUseCase(ISystemMessageStore store, SystemMessageNotifier notifier) {
        this.store = store;
        this.notifier = notifier != null ? notifier : SystemMessageNotifier.NOOP;
    }

    public SystemMessageSummary publishToUsers(SystemMessage message, List<String> userIds) {
        message.setChannelId(Preconditions.requireText(message.getChannelId(), "channelId"));
        message.setTitle(Preconditions.requireText(message.getTitle(), "title"));
        message.setContent(Preconditions.requireText(message.getContent(), "content"));
        LinkedHashSet<String> targets = new LinkedHashSet<>(userIds != null ? userIds : List.of());
        targets.removeIf(userId -> userId == null || userId.isBlank());
        if (targets.isEmpty()) {
            throw new ValidationException("targetUserIds is required");
        }

        long now = System.currentTimeMillis();
        if (message.getMessageId() == null || message.getMessageId().isBlank()) {
            message.setMessageId(IdGenerator.next("sysmsg"));
        }
        message.setCreatedAt(now);
        message.setSummary(message.getSummary() != null ? message.getSummary() : "");
        message.setContentType(message.getContentType() != null ? message.getContentType() : "text");
        message.setSenderType(message.getSenderType() != null ? message.getSenderType() : "system");
        message.setSenderId(message.getSenderId() != null ? message.getSenderId() : "im-system");

        store.ensureChannel(defaultChannel(message.getChannelId(), now));
        store.saveMessage(message);
        for (String userId : targets) {
            store.addInbox(message.getMessageId(), userId, message.getChannelId(), now);
        }

        SystemMessageSummary summary = toSummary(message, message.getChannelId());
        notifier.notify(List.copyOf(targets), summary);
        return summary;
    }

    private static SystemChannel defaultChannel(String channelId, long now) {
        SystemChannel channel = new SystemChannel();
        channel.setChannelId(channelId);
        channel.setChannelName(channelId);
        channel.setChannelType("system");
        channel.setDescription("");
        channel.setStatus(1);
        channel.setCreatedAt(now);
        channel.setUpdatedAt(now);
        return channel;
    }

    private static SystemMessageSummary toSummary(SystemMessage message, String channelName) {
        SystemMessageSummary summary = new SystemMessageSummary();
        summary.setMessageId(message.getMessageId());
        summary.setChannelId(message.getChannelId());
        summary.setChannelName(channelName);
        summary.setTitle(message.getTitle());
        summary.setSummary(message.getSummary());
        summary.setPriority(message.getPriority());
        summary.setCreatedAt(message.getCreatedAt());
        return summary;
    }
}
