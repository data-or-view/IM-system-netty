package com.im.bootstrap;

import io.lettuce.core.RedisURI;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

final class E2ETestConfig {

    private static final String DEFAULT_MYSQL_URL =
            "jdbc:mysql://127.0.0.1:3306/im_system?useUnicode=true&characterEncoding=utf-8&useSSL=false" +
                    "&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true";
    static final String TOKEN_SECRET = "e2e-test-secret-256-bit-minimum-key";
    static final String TEST_PASSWORD = "123456";

    private E2ETestConfig() {
    }

    static Map<String, String> infrastructureDefaults() {
        Map<String, String> defaults = new LinkedHashMap<>();
        defaults.put("im.db.jdbc-url", env("IM_E2E_MYSQL_JDBC_URL", "IM_IT_MYSQL_JDBC_URL", DEFAULT_MYSQL_URL));
        defaults.put("im.db.username", env("IM_E2E_MYSQL_USER", "IM_IT_MYSQL_USER", "root"));
        defaults.put("im.db.password", env("IM_E2E_MYSQL_PASSWORD", "IM_IT_MYSQL_PASSWORD", "123456"));
        defaults.put("im.redis.host", env("IM_E2E_REDIS_HOST", "IM_REDIS_HOST", "127.0.0.1"));
        defaults.put("im.redis.port", env("IM_E2E_REDIS_PORT", "IM_REDIS_PORT", "6379"));
        defaults.put("im.redis.username", env("IM_E2E_REDIS_USERNAME", "IM_REDIS_USERNAME", ""));
        defaults.put("im.redis.password", env("IM_E2E_REDIS_PASSWORD", "IM_REDIS_PASSWORD", "difyai123456"));
        defaults.put("im.redis.database", env("IM_E2E_REDIS_DATABASE", "IM_REDIS_DATABASE", "0"));
        return defaults;
    }

    static void putInfrastructureDefaults(Map<String, String> config) {
        infrastructureDefaults().forEach(config::putIfAbsent);
    }

    static RedisURI redisUri() {
        Map<String, String> config = infrastructureDefaults();
        RedisURI.Builder builder = RedisURI.builder()
                .withHost(config.get("im.redis.host"))
                .withPort(parseInt(config.get("im.redis.port"), 6379))
                .withDatabase(parseInt(config.get("im.redis.database"), 0))
                .withTimeout(Duration.ofSeconds(3));
        String username = config.get("im.redis.username");
        String password = config.get("im.redis.password");
        if (username != null && !username.isBlank()) {
            builder.withAuthentication(username, password != null ? password.toCharArray() : new char[0]);
        } else if (password != null && !password.isBlank()) {
            builder.withPassword(password.toCharArray());
        }
        return builder.build();
    }

    private static String env(String primary, String secondary, String defaultValue) {
        if (System.getenv().containsKey(primary)) {
            return System.getenv(primary);
        }
        if (System.getenv().containsKey(secondary)) {
            return System.getenv(secondary);
        }
        return defaultValue;
    }

    private static int parseInt(String value, int defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return Integer.parseInt(value);
    }
}
