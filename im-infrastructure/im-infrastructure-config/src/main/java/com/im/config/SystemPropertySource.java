package com.im.config;

import java.util.HashMap;
import java.util.Map;

/**
 * 系统属性数据源（order=1）。
 *
 * <p>从 {@link System#getProperties()} 读取。只加载指定前缀的键（默认 {@code im.}），
 * 避免 JVM 自身属性污染命名空间。
 */
public class SystemPropertySource implements ConfigSource {

    private final String prefix;

    public SystemPropertySource() {
        this("im.");
    }

    public SystemPropertySource(String prefix) {
        this.prefix = prefix;
    }

    @Override
    public int order() {
        return 1;
    }

    @Override
    public Map<String, String> load() {
        Map<String, String> result = new HashMap<>();
        for (Map.Entry<Object, Object> entry : System.getProperties().entrySet()) {
            String key = entry.getKey().toString();
            if (key.startsWith(prefix)) {
                result.put(key, entry.getValue().toString());
            }
        }
        return Map.copyOf(result);
    }
}
