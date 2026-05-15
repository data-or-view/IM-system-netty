package com.im.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 配置加载器。
 *
 * <p>启动时加载所有配置数据源，按优先级合并后对外提供统一的 {@link Config} 查询接口。
 *
 * <p>使用方式：
 * <pre>{@code
 * Config config = ConfigLoader.load();
 * String host = config.getRequiredString("redis.host");
 * }</pre>
 */
public final class ConfigLoader {

    private static final Logger log = LoggerFactory.getLogger(ConfigLoader.class);

    private static volatile Config config;

    private ConfigLoader() {}

    /**
     * 加载全部配置并返回合并后的 {@link Config}。
     *
     * <p>加载顺序：
     * <ol>
     *   <li>创建内置数据源（环境变量 → 系统属性 → 配置文件）</li>
     *   <li>按 {@link ConfigSource#order()} 排序（值越小优先级越高）</li>
     *   <li>依次加载，高优先级覆盖低优先级</li>
     *   <li>包装为统一的 {@link Config} 并缓存</li>
     * </ol>
     *
     * @return 合并后的配置（单例，首次调用后缓存）
     */
    public static Config load() {
        if (config != null) return config;
        return doLoad();
    }

    /**
     * 重新加载配置（清空缓存后重建）。
     */
    public static synchronized Config reload() {
        config = null;
        return load();
    }

    private static synchronized Config doLoad() {
        if (config != null) return config;

        // 收集所有数据源
        List<ConfigSource> sources = new ArrayList<>();
        sources.add(new EnvSource());
        sources.add(new SystemPropertySource());
        sources.add(new PropertyFileSource());
        sources.addAll(customSources);

        // 按 order 升序排序（高优先级在前）
        sources.sort(Comparator.comparingInt(ConfigSource::order));

        log.info("Loading configuration from {} sources", sources.size());

        // 依次加载，构建 CompositeConfig（组合模式）
        CompositeConfig.Builder builder = CompositeConfig.builder();
        for (ConfigSource source : sources) {
            var data = source.load();
            if (!data.isEmpty()) {
                log.debug("  [order={}] {}: {} keys", source.order(),
                        source.getClass().getSimpleName(), data.size());
                builder.add(new MapConfig(data));
            }
        }

        config = builder.build();
        log.info("Configuration loaded: {} total keys", countKeys(config));
        return config;
    }

    // ========== 自定义数据源注册 ==========

    private static final List<ConfigSource> customSources = new ArrayList<>();

    /**
     * 注册自定义配置数据源。
     * <p>必须在首次调用 {@link #load()} 之前注册，否则需要通过 {@link #reload()} 重新加载。
     */
    public static synchronized void register(ConfigSource source) {
        customSources.add(source);
        log.info("Registered custom config source: {} (order={})",
                source.getClass().getSimpleName(), source.order());
    }

    /** 获取预估的键总数（仅用于日志）。 */
    private static int countKeys(Config cfg) {
        // 简易估算：这里无法精确统计，留待后续完善
        return -1;
    }
}
