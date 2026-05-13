package com.im.core.config;

import java.util.Locale;

/**
 * 环境变量配置源。
 *
 * <p>键转换规则：{@code im.db.enabled} → {@code IM_DB_ENABLED}（点转下划线，大写）。
 * <p>优先级 100，次高。
 */
public class EnvPropertySource implements PropertySource {

    @Override
    public String get(String key) {
        String envKey = key
                .replace('.', '_')
                .replace('-', '_')
                .toUpperCase(Locale.ROOT);
        return System.getenv(envKey);
    }

    @Override
    public int order() { return 100; }

    @Override
    public String description() { return "Environment Variables"; }
}
