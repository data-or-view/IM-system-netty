package com.im.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Properties 配置文件数据源。
 *
 * <p>从 classpath 加载 {@code .properties} 文件。
 * 默认路径：{@code application.properties}。
 * 文件不存在时返回空配置（不抛异常）。
 *
 * <p>文件可定义 {@code _config.order} 键来指定优先级顺序。
 * 不指定时默认 {@code order=200}。
 */
public class PropertyFileSource implements ConfigSource {

    private static final Logger log = LoggerFactory.getLogger(PropertyFileSource.class);

    /** 配置元信息键：定义该文件的优先级顺序（不在实际配置中暴露）。 */
    public static final String ORDER_KEY = "_config.order";
    private static final int DEFAULT_ORDER = 200;

    private final String resourcePath;
    private final int sourceOrder;
    private final Map<String, String> data;

    public PropertyFileSource() {
        this("application.properties");
    }

    public PropertyFileSource(String resourcePath) {
        this.resourcePath = resourcePath;
        var result = doLoad();
        this.sourceOrder = result.order;
        this.data = result.data;
    }

    @Override
    public int order() {
        return sourceOrder;
    }

    @Override
    public Map<String, String> load() {
        return data;
    }

    private LoadResult doLoad() {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) cl = PropertyFileSource.class.getClassLoader();

        try (InputStream in = cl.getResourceAsStream(resourcePath)) {
            if (in == null) {
                log.debug("Properties not found: {}", resourcePath);
                return new LoadResult(DEFAULT_ORDER, Map.of());
            }
            Properties props = new Properties();
            props.load(in);

            int order = DEFAULT_ORDER;
            Map<String, String> result = new HashMap<>();
            for (String key : props.stringPropertyNames()) {
                String value = props.getProperty(key).trim();
                if (ORDER_KEY.equals(key)) {
                    order = Integer.parseInt(value);
                } else {
                    result.put(key, value);
                }
            }
            log.info("Loaded {} properties from {} (order={})", result.size(), resourcePath, order);
            return new LoadResult(order, Map.copyOf(result));
        } catch (IOException e) {
            log.warn("Failed to load {}: {}", resourcePath, e.getMessage());
            return new LoadResult(DEFAULT_ORDER, Map.of());
        }
    }

    private record LoadResult(int order, Map<String, String> data) {}
}
