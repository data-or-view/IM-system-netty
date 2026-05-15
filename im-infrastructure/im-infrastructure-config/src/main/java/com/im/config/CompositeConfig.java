package com.im.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 组合配置（Composite）。
 *
 * <p>持有多个按优先级排序的 {@link Config} 子节点。
 * 查询时按优先级从高到低遍历，返回第一个非空值。
 * 高优先级子节点先添加。
 *
 * <p>用法：
 * <pre>{@code
 * Config config = CompositeConfig.builder()
 *     .add(new SystemPropertyConfig("im."))    // 最高优先级
 *     .add(new EnvConfig("IM_"))
 *     .add(new PropertyConfig("application.properties"))  // 基础配置
 *     .build();
 * }</pre>
 */
public class CompositeConfig implements Config {

    private static final Logger log = LoggerFactory.getLogger(CompositeConfig.class);

    private final List<Config> sources;

    CompositeConfig(List<Config> sources) {
        this.sources = List.copyOf(sources);
        log.info("CompositeConfig created with {} sources", sources.size());
    }

    @Override
    public Optional<String> getString(String key) {
        for (Config source : sources) {
            Optional<String> value = source.getString(key);
            if (value.isPresent()) return value;
        }
        return Optional.empty();
    }

    @Override
    public Optional<Integer> getInt(String key) {
        for (Config source : sources) {
            Optional<Integer> value = source.getInt(key);
            if (value.isPresent()) return value;
        }
        return Optional.empty();
    }

    @Override
    public Optional<Long> getLong(String key) {
        for (Config source : sources) {
            Optional<Long> value = source.getLong(key);
            if (value.isPresent()) return value;
        }
        return Optional.empty();
    }

    @Override
    public Optional<Boolean> getBoolean(String key) {
        for (Config source : sources) {
            Optional<Boolean> value = source.getBoolean(key);
            if (value.isPresent()) return value;
        }
        return Optional.empty();
    }

    @Override
    public Optional<Duration> getDuration(String key) {
        for (Config source : sources) {
            Optional<Duration> value = source.getDuration(key);
            if (value.isPresent()) return value;
        }
        return Optional.empty();
    }

    @Override
    public boolean hasKey(String key) {
        for (Config source : sources) {
            if (source.hasKey(key)) return true;
        }
        return false;
    }

    // ========== Builder ==========

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final List<Config> sources = new ArrayList<>();

        Builder() {}

        /** 添加配置源，先添加的优先级更高。 */
        public Builder add(Config source) {
            sources.add(source);
            return this;
        }

        public CompositeConfig build() {
            return new CompositeConfig(new ArrayList<>(sources));
        }
    }
}
