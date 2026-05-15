package com.im.spi;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * SPI 加载器。
 *
 * <p>从 {@code META-INF/services/} 目录下读取扩展点实现信息，按名称缓存实例。
 * 每个 SPI 接口只会加载一次，后续直接从缓存返回。
 *
 * <p>文件格式（每行）：
 * <pre>
 * name=com.example.ImplClass
 * </pre>
 *
 * <p>使用方式：
 * <pre>{@code
 * Cache<String, String> cache = SpiLoader.load(Cache.class, "redis");
 * RetryExecutor exec = SpiLoader.loadDefault(RetryExecutor.class);
 * }</pre>
 */
public final class SpiLoader {

    private SpiLoader() {}

    /** 全局缓存：接口 → (名称 → 实例) */
    private static final Map<Class<?>, Map<String, Object>> CACHE = new ConcurrentHashMap<>();

    /**
     * 按名称加载实现。
     *
     * @param type SPI 接口
     * @param name 实现名称
     * @return 缓存的单例实例
     * @throws IllegalArgumentException 没有找到对应名称的实现
     */
    @SuppressWarnings("unchecked")
    public static <T> T load(Class<T> type, String name) {
        Map<String, Object> impls = resolve(type);
        Object instance = impls.get(name);
        if (instance == null) {
            throw new IllegalArgumentException("No SPI implementation found: "
                    + type.getName() + " name=" + name
                    + ". Available: " + impls.keySet());
        }
        return (T) instance;
    }

    /**
     * 加载默认实现（由 {@link Spi#value()} 指定）。
     */
    @SuppressWarnings("unchecked")
    public static <T> T loadDefault(Class<T> type) {
        Map<String, Object> impls = resolve(type);
        List<String> candidates = impls.keySet().stream().toList();

        // 优先使用 @Spi 上的默认值
        Spi spi = type.getAnnotation(Spi.class);
        if (spi != null && !spi.value().isEmpty()) {
            Object instance = impls.get(spi.value());
            if (instance != null) {
                return (T) instance;
            }
        }

        // 没有默认配置或默认配置不存在，取第一个
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException("No SPI implementations found for: " + type.getName());
        }
        return (T) impls.get(candidates.get(0));
    }

    /**
     * 获取所有实现。
     */
    public static <T> Map<String, T> loadAll(Class<T> type) {
        @SuppressWarnings("unchecked")
        Map<String, T> result = (Map<String, T>) (Map<?, ?>) resolve(type);
        return result;
    }

    // ========== 内部 ==========

    /** 解析并缓存一个 SPI 接口的所有实现 */
    private static Map<String, Object> resolve(Class<?> type) {
        return CACHE.computeIfAbsent(type, SpiLoader::doLoad);
    }

    /** 实际读取 META-INF/services 文件 */
    private static Map<String, Object> doLoad(Class<?> type) {
        String resource = "META-INF/services/" + type.getName();
        Map<String, Object> result = new ConcurrentHashMap<>();

        try {
            var resources = Thread.currentThread()
                    .getContextClassLoader()
                    .getResources(resource);

            while (resources.hasMoreElements()) {
                var url = resources.nextElement();
                try (var stream = url.openStream();
                     var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {

                    for (String line : reader.lines().collect(Collectors.toList())) {
                        line = line.trim();
                        if (line.isEmpty() || line.startsWith("#")) continue;

                        int eq = line.indexOf('=');
                        if (eq == -1) {
                            // 没有等号，直接当全类名处理（兼容原生 ServiceLoader 格式）
                            tryCreate(type, result, line, line);
                            continue;
                        }

                        String name = line.substring(0, eq).trim();
                        String className = line.substring(eq + 1).trim();
                        tryCreate(type, result, name, className);
                    }
                }
            }
        } catch (Exception e) {
            // 文件不存在或读取失败：不是错误，只是没有注册
        }

        return result;
    }

    private static void tryCreate(Class<?> spiType, Map<String, Object> result,
                                   String name, String className) {
        try {
            Class<?> clazz = Class.forName(className);
            result.put(name, clazz.getDeclaredConstructor().newInstance());
        } catch (Exception e) {
            // 类不存在或不能实例化：跳过，不阻断其他实现
        }
    }
}
