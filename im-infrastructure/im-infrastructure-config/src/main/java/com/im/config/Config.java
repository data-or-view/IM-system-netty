package com.im.config;

import java.time.Duration;
import java.util.Optional;

/**
 * 统一配置接口（组件接口 / Component）。
 *
 * <p>支持多数据源合并，配合 {@link CompositeConfig} 实现组合模式。
 * 配置键使用点号分隔：{@code "redis.host"}、{@code "server.port"}。
 *
 * <p>实现类只需实现 {@code getXxx(key)} 的 Optional 版本和 {@link #hasKey(String)}，
 * 带默认值的变体和 {@code getRequiredXxx} 由接口默认方法提供。
 */
public interface Config {

    // ========== String ==========

    /** 获取字符串值，缺失返回 {@link Optional#empty()}。 */
    Optional<String> getString(String key);

    /** 获取字符串值，缺失返回 {@code defaultValue}。 */
    default String getString(String key, String defaultValue) {
        return getString(key).orElse(defaultValue);
    }

    // ========== Integer ==========

    /** 获取整数值，缺失或格式非法返回 {@link Optional#empty()}。 */
    Optional<Integer> getInt(String key);

    /** 获取整数值，缺失或格式非法返回 {@code defaultValue}。 */
    default int getInt(String key, int defaultValue) {
        return getInt(key).orElse(defaultValue);
    }

    // ========== Long ==========

    /** 获取长整数值，缺失或格式非法返回 {@link Optional#empty()}。 */
    Optional<Long> getLong(String key);

    /** 获取长整数值，缺失或格式非法返回 {@code defaultValue}。 */
    default long getLong(String key, long defaultValue) {
        return getLong(key).orElse(defaultValue);
    }

    // ========== Boolean ==========

    /** 获取布尔值（"true"/"false"，大小写不敏感），缺失返回 {@link Optional#empty()}。 */
    Optional<Boolean> getBoolean(String key);

    /** 获取布尔值，缺失返回 {@code defaultValue}。 */
    default boolean getBoolean(String key, boolean defaultValue) {
        return getBoolean(key).orElse(defaultValue);
    }

    // ========== Duration ==========

    /** 获取 Duration（支持 ISO-8601 "PT30S" 及简写 "30s"/"5m"/"1h"/"2d"），缺失返回 {@link Optional#empty()}。 */
    Optional<Duration> getDuration(String key);

    /** 获取 Duration，缺失或格式非法返回 {@code defaultValue}。 */
    default Duration getDuration(String key, Duration defaultValue) {
        return getDuration(key).orElse(defaultValue);
    }

    // ========== Required（缺失抛异常） ==========

    /** 获取必需字符串，缺失抛 {@link ConfigException}。 */
    default String getRequiredString(String key) {
        return getString(key).orElseThrow(() -> new ConfigException("Missing required config key: " + key));
    }

    /** 获取必需整数，缺失或格式非法抛 {@link ConfigException}。 */
    default int getRequiredInt(String key) {
        return getInt(key).orElseThrow(() -> new ConfigException("Missing required config key: " + key));
    }

    /** 获取必需长整数，缺失或格式非法抛 {@link ConfigException}。 */
    default long getRequiredLong(String key) {
        return getLong(key).orElseThrow(() -> new ConfigException("Missing required config key: " + key));
    }

    /** 获取必需布尔值，缺失抛 {@link ConfigException}。 */
    default boolean getRequiredBoolean(String key) {
        return getBoolean(key).orElseThrow(() -> new ConfigException("Missing required config key: " + key));
    }

    /** 获取必需 Duration，缺失或格式非法抛 {@link ConfigException}。 */
    default Duration getRequiredDuration(String key) {
        return getDuration(key).orElseThrow(() -> new ConfigException("Missing required config key: " + key));
    }

    // ========== 查询 ==========

    /** 返回 true 如果配置键存在。 */
    boolean hasKey(String key);
}
