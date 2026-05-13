package com.im.core.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 多级配置源聚合。
 *
 * <p>按 {@link PropertySource#order()} 从小到大排序，查询时返回第一个非 {@code null} 值。
 * 支持 {@code getInt}、{@code getBool}、{@code getString} 等便捷方法。
 *
 * <pre>
 * PropertySources props = PropertySources.builder()
 *     .add(new SystemPropertySource())
 *     .add(new EnvPropertySource())
 *     .add(new YamlPropertySource("config/application.yml"))
 *     .add(new DefaultPropertySource("im.server.port", "8080"))
 *     .build();
 * </pre>
 */
public class PropertySources {

    private static final Logger log = LoggerFactory.getLogger(PropertySources.class);

    private final List<PropertySource> sources;

    private PropertySources(List<PropertySource> sources) {
        this.sources = sources;
    }

    // ── 查询 ──

    /** 获取字符串值，无此键时返回 {@code null}。 */
    public String get(String key) {
        for (PropertySource ps : sources) {
            if (ps.isAvailable()) {
                String val = ps.get(key);
                if (val != null) return val;
            }
        }
        return null;
    }

    /** 获取字符串值，无此键时返回 {@code defaultValue}。 */
    public String getString(String key, String defaultValue) {
        String val = get(key);
        return val != null ? val : defaultValue;
    }

    /** 获取整数。 */
    public int getInt(String key, int defaultValue) {
        String val = get(key);
        if (val == null) return defaultValue;
        try {
            return Integer.parseInt(val.trim());
        } catch (NumberFormatException e) {
            log.warn("Invalid int for key '{}': '{}', using default {}", key, val, defaultValue);
            return defaultValue;
        }
    }

    /** 获取长整数。 */
    public long getLong(String key, long defaultValue) {
        String val = get(key);
        if (val == null) return defaultValue;
        try {
            return Long.parseLong(val.trim());
        } catch (NumberFormatException e) {
            log.warn("Invalid long for key '{}': '{}', using default {}", key, val, defaultValue);
            return defaultValue;
        }
    }

    /** 获取布尔值（"true"/"false"，不区分大小写）。 */
    public boolean getBool(String key, boolean defaultValue) {
        String val = get(key);
        if (val == null) return defaultValue;
        return "true".equalsIgnoreCase(val.trim());
    }

    /** 获取列表（逗号分隔）。 */
    public List<String> getList(String key) {
        String val = get(key);
        if (val == null || val.isEmpty()) return List.of();
        return Arrays.stream(val.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    /** 获取整个 Map（用于枚举某前缀下的所有键, 0%x 如 im.redis.*）。 */
    public Map<String, String> getPrefixed(String prefix) {
        Map<String, String> result = new LinkedHashMap<>();
        for (PropertySource ps : sources) {
            if (ps instanceof MapBackedPropertySource) {
                Map<String, String> sub = ((MapBackedPropertySource) ps).getPrefixed(prefix);
                sub.forEach(result::putIfAbsent);
            }
        }
        return result;
    }

    // ── 诊断 ──

    public void logSources() {
        log.info("Configuration sources:");
        for (PropertySource ps : sources) {
            String status = ps.isAvailable() ? "✓" : "✗";
            log.info("  [{}] order={} {}: {}", status, ps.order(), ps.description(), ps.isAvailable() ? "active" : "unavailable");
        }
    }

    // ── Builder ──

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final List<PropertySource> list = new ArrayList<>();

        public Builder add(PropertySource ps) {
            list.add(ps);
            return this;
        }

        public PropertySources build() {
            list.sort(Comparator.comparingInt(PropertySource::order));
            return new PropertySources(Collections.unmodifiableList(new ArrayList<>(list)));
        }
    }
}
