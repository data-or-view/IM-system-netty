package com.im.core.config;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 基于 {@code Map<String, String>} 的配置源基类。
 *
 * <p>支持 {@link PropertySources#getPrefixed} 前缀枚举。
 */
public abstract class MapBackedPropertySource implements PropertySource {

    protected final Map<String, String> entries;

    protected MapBackedPropertySource(Map<String, String> entries) {
        this.entries = new LinkedHashMap<>(entries);
    }

    @Override
    public String get(String key) {
        return entries.get(key);
    }

    /**
     * 返回所有键以 {@code prefix} 开头的条目（去掉前缀后）。
     */
    public Map<String, String> getPrefixed(String prefix) {
        Map<String, String> result = new LinkedHashMap<>();
        String dotPrefix = prefix.endsWith(".") ? prefix : prefix + ".";
        for (Map.Entry<String, String> e : entries.entrySet()) {
            if (e.getKey().startsWith(dotPrefix)) {
                result.put(e.getKey().substring(dotPrefix.length()), e.getValue());
            }
        }
        return result;
    }
}
