package com.im.core.ratelimit;

import com.im.api.ApiRequest;
import com.im.api.Operation;
import com.im.config.Config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Static rate limit policy for high-risk IM operations.
 */
public final class RateLimitPolicy {

    private static final String DEFAULT_KEY_PREFIX = "im:rl:";
    private static final String UNKNOWN = "unknown";
    private static final String ANONYMOUS = "anonymous";

    private final Map<Operation, List<RateLimitRule>> rulesByOperation;

    private RateLimitPolicy(Map<Operation, List<RateLimitRule>> rulesByOperation) {
        this.rulesByOperation = rulesByOperation;
    }

    public static RateLimitPolicy defaults(String keyPrefix) {
        return build(normalizePrefix(keyPrefix), RuleConfig.defaultConfig());
    }

    public static RateLimitPolicy fromConfig(Config config) {
        Objects.requireNonNull(config, "config");
        String keyPrefix = config.getString("im.rate-limit.key-prefix", DEFAULT_KEY_PREFIX);
        RuleConfig ruleConfig = RuleConfig.fromConfig(config);
        return build(normalizePrefix(keyPrefix), ruleConfig);
    }

    public List<RateLimitRule> rulesFor(ApiRequest request) {
        if (request == null || request.op() == null) {
            return List.of();
        }
        return rulesByOperation.getOrDefault(request.op(), List.of());
    }

    private static RateLimitPolicy build(String keyPrefix, RuleConfig config) {
        Map<Operation, List<RateLimitRule>> grouped = new EnumMap<>(Operation.class);
        for (RuleDefinition definition : defaultDefinitions()) {
            RuleSetting setting = config.setting(definition.name(), definition.defaultLimit(), definition.defaultWindow());
            RateLimitRule rule = new RateLimitRule(
                    definition.name(),
                    definition.scope(),
                    setting.limit(),
                    setting.window(),
                    keyPrefix,
                    definition.identityExtractor());
            for (Operation operation : definition.operations()) {
                grouped.computeIfAbsent(operation, ignored -> new ArrayList<>()).add(rule);
            }
        }
        grouped.replaceAll((operation, rules) -> List.copyOf(rules));
        return new RateLimitPolicy(Map.copyOf(grouped));
    }

    private static List<RuleDefinition> defaultDefinitions() {
        return List.of(
                rule("login.ip", RateLimitScope.IP, 10, Duration.ofMinutes(1), RateLimitPolicy::clientIp,
                        Operation.LOGIN),
                rule("login.user", RateLimitScope.LOGIN_USER, 5, Duration.ofMinutes(1), RateLimitPolicy::loginUser,
                        Operation.LOGIN),
                rule("register.ip", RateLimitScope.IP, 5, Duration.ofMinutes(5), RateLimitPolicy::clientIp,
                        Operation.REGISTER, Operation.USER_REGISTER),

                rule("chat-send.user", RateLimitScope.USER, 120, Duration.ofMinutes(1), RateLimitPolicy::userId,
                        Operation.CHAT_SEND),
                rule("chat-send.user-target", RateLimitScope.USER_TARGET, 60, Duration.ofMinutes(1), RateLimitPolicy::userTarget,
                        Operation.CHAT_SEND),
                rule("chat-send-group.user", RateLimitScope.USER, 120, Duration.ofMinutes(1), RateLimitPolicy::userId,
                        Operation.CHAT_SEND_GROUP),
                rule("chat-send-group.user-group", RateLimitScope.USER_GROUP, 60, Duration.ofMinutes(1), RateLimitPolicy::userGroup,
                        Operation.CHAT_SEND_GROUP),

                rule("friend-apply.user", RateLimitScope.USER, 30, Duration.ofHours(1), RateLimitPolicy::userId,
                        Operation.FRIEND_APPLY),
                rule("friend-apply.user-target", RateLimitScope.USER_TARGET, 3, Duration.ofHours(24), RateLimitPolicy::userTarget,
                        Operation.FRIEND_APPLY),
                rule("group-join.user", RateLimitScope.USER, 30, Duration.ofHours(1), RateLimitPolicy::userId,
                        Operation.GROUP_JOIN),
                rule("group-join.user-group", RateLimitScope.USER_GROUP, 3, Duration.ofHours(24), RateLimitPolicy::userGroup,
                        Operation.GROUP_JOIN),

                rule("file-upload.user", RateLimitScope.USER, 120, Duration.ofMinutes(10), RateLimitPolicy::userId,
                        Operation.FILE_UPLOAD, Operation.FILE_UPLOAD_SIGN, Operation.FILE_UPLOAD_COMPLETE,
                        Operation.FILE_MULTIPART_INIT,
                        Operation.FILE_MULTIPART_PART_SIGN, Operation.FILE_MULTIPART_UPLOAD,
                        Operation.FILE_MULTIPART_COMPLETE, Operation.FILE_MULTIPART_ABORT),

                rule("admin-system-message.user", RateLimitScope.USER, 20, Duration.ofMinutes(1), RateLimitPolicy::userId,
                        Operation.ADMIN_SYSTEM_MESSAGE_PUBLISH),
                rule("admin-system-message.global", RateLimitScope.GLOBAL, 60, Duration.ofMinutes(1), ignored -> "global",
                        Operation.ADMIN_SYSTEM_MESSAGE_PUBLISH)
        );
    }

    private static RuleDefinition rule(String name,
                                       RateLimitScope scope,
                                       int defaultLimit,
                                       Duration defaultWindow,
                                       Function<ApiRequest, String> identityExtractor,
                                       Operation... operations) {
        return new RuleDefinition(name, scope, defaultLimit, defaultWindow, identityExtractor, List.of(operations));
    }

    private static String normalizePrefix(String keyPrefix) {
        String value = keyPrefix == null || keyPrefix.isBlank() ? DEFAULT_KEY_PREFIX : keyPrefix;
        return value.endsWith(":") ? value : value + ":";
    }

    private static String clientIp(ApiRequest request) {
        return attribute(request, ApiRequest.ATTR_CLIENT_IP, UNKNOWN);
    }

    private static String loginUser(ApiRequest request) {
        return firstParam(request, UNKNOWN, "userId", "username", "phone", "email", "account");
    }

    private static String userId(ApiRequest request) {
        String currentUserId = request.currentUserId();
        if (currentUserId != null && !currentUserId.isBlank()) {
            return currentUserId;
        }
        return ANONYMOUS;
    }

    private static String userTarget(ApiRequest request) {
        return userId(request) + ":" + firstParam(request, UNKNOWN,
                "toUserId", "targetUserId", "friendUserId", "targetId", "userId");
    }

    private static String userGroup(ApiRequest request) {
        return userId(request) + ":" + firstParam(request, UNKNOWN, "groupId", "targetGroupId");
    }

    private static String attribute(ApiRequest request, String key, String defaultValue) {
        Object value = request.attribute(key);
        return value != null && !value.toString().isBlank() ? value.toString() : defaultValue;
    }

    private static String firstParam(ApiRequest request, String defaultValue, String... names) {
        for (String name : names) {
            Object value = request.params().get(name);
            if (value != null && !value.toString().isBlank()) {
                return value.toString();
            }
        }
        return defaultValue;
    }

    private record RuleDefinition(String name,
                                  RateLimitScope scope,
                                  int defaultLimit,
                                  Duration defaultWindow,
                                  Function<ApiRequest, String> identityExtractor,
                                  List<Operation> operations) {
    }

    private record RuleSetting(int limit, Duration window) {
    }

    private record RuleConfig(Config config) {

        static RuleConfig defaultConfig() {
            return new RuleConfig(null);
        }

        static RuleConfig fromConfig(Config config) {
            return new RuleConfig(config);
        }

        RuleSetting setting(String ruleName, int defaultLimit, Duration defaultWindow) {
            if (config == null) {
                return new RuleSetting(defaultLimit, defaultWindow);
            }
            int limit = config.getInt("im.rate-limit." + ruleName + ".limit", defaultLimit);
            long windowSeconds = config.getLong("im.rate-limit." + ruleName + ".window-seconds",
                    defaultWindow.toSeconds());
            return new RuleSetting(limit, Duration.ofSeconds(windowSeconds));
        }
    }
}
