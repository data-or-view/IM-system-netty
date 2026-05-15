package com.im.config;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * 基于 {@link Map} 的配置实现（叶子基类 / Leaf）。
 *
 * <p>数据在构造时已加载完毕，查询走内部 map。
 */
class MapConfig implements Config {

    private final Map<String, String> data;

    MapConfig(Map<String, String> data) {
        this.data = Map.copyOf(data);
    }

    @Override
    public Optional<String> getString(String key) {
        return Optional.ofNullable(data.get(key));
    }

    @Override
    public Optional<Integer> getInt(String key) {
        String val = data.get(key);
        if (val == null) return Optional.empty();
        try {
            return Optional.of(Integer.parseInt(val.trim()));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<Long> getLong(String key) {
        String val = data.get(key);
        if (val == null) return Optional.empty();
        try {
            return Optional.of(Long.parseLong(val.trim()));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<Boolean> getBoolean(String key) {
        String val = data.get(key);
        if (val == null) return Optional.empty();
        return Optional.of("true".equalsIgnoreCase(val.trim())
                || "1".equals(val.trim()));
    }

    @Override
    public Optional<Duration> getDuration(String key) {
        String val = data.get(key);
        if (val == null) return Optional.empty();
        try {
            return Optional.of(parseDuration(val.trim()));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    @Override
    public boolean hasKey(String key) {
        return data.containsKey(key);
    }

    /** 返回内部数据快照（供子类及 CompositeConfig 调试使用）。 */
    Map<String, String> dump() {
        return data;
    }

    private static Duration parseDuration(String s) {
        // 简写格式：30s, 5m, 1h, 2d
        if (s.matches("\\d+\\s*[smhd]")) {
            char unit = s.charAt(s.length() - 1);
            long amount = Long.parseLong(s.substring(0, s.length() - 1).trim());
            return switch (unit) {
                case 's' -> Duration.ofSeconds(amount);
                case 'm' -> Duration.ofMinutes(amount);
                case 'h' -> Duration.ofHours(amount);
                case 'd' -> Duration.ofDays(amount);
                default -> Duration.parse(s);
            };
        }
        return Duration.parse(s);
    }
}
