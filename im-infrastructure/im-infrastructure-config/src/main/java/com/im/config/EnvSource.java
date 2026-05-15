package com.im.config;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 环境变量数据源（order=0，最高优先级）。
 *
 * <p>从 {@link System#getenv()} 读取。变量名转换为小写 + 下划线变点号，
 * 例如 {@code IM_REDIS_HOST → im.redis.host}。
 * 只加载指定前缀的环境变量（默认 {@code IM_}）。
 */
public class EnvSource implements ConfigSource {

    private final String prefix;

    public EnvSource() {
        this("IM_");
    }

    public EnvSource(String prefix) {
        this.prefix = prefix;
    }

    @Override
    public int order() {
        return 0;
    }

    @Override
    public Map<String, String> load() {
        Map<String, String> result = new HashMap<>();
        for (Map.Entry<String, String> entry : System.getenv().entrySet()) {
            String envKey = entry.getKey();
            if (envKey.startsWith(prefix)) {
                String configKey = envKey.toLowerCase(Locale.ROOT).replace('_', '.');
                result.put(configKey, entry.getValue());
            }
        }
        return Map.copyOf(result);
    }
}
