package com.im.core.handler.unified;

import com.im.common.exception.ValidationException;
import com.im.config.Config;

import java.util.Objects;

/**
 * Configured bounds for client-driven message queries.
 */
public record MessageQueryLimits(int maxPullLimit, int maxSyncConversations) {

    public static final int DEFAULT_MAX_PULL_LIMIT = 100;
    public static final int DEFAULT_MAX_SYNC_CONVERSATIONS = 20;

    public MessageQueryLimits {
        if (maxPullLimit <= 0) {
            throw new IllegalArgumentException("maxPullLimit must be positive");
        }
        if (maxSyncConversations <= 0) {
            throw new IllegalArgumentException("maxSyncConversations must be positive");
        }
    }

    public static MessageQueryLimits defaults() {
        return new MessageQueryLimits(DEFAULT_MAX_PULL_LIMIT, DEFAULT_MAX_SYNC_CONVERSATIONS);
    }

    public static MessageQueryLimits from(Config config) {
        Objects.requireNonNull(config, "config");
        return new MessageQueryLimits(
                config.getInt("im.message.pull.max-limit", DEFAULT_MAX_PULL_LIMIT),
                config.getInt("im.message.sync.max-conversations", DEFAULT_MAX_SYNC_CONVERSATIONS));
    }

    public int clampPullLimit(int requested) {
        if (requested <= 0) {
            throw new ValidationException("limit must be positive");
        }
        return Math.min(requested, maxPullLimit);
    }
}
