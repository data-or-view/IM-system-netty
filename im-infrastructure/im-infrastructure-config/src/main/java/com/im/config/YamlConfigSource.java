package com.im.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;

/**
 * YAML 配置文件数据源。
 *
 * <p>从 classpath 或文件系统加载 YAML 文件，将嵌套结构拍平成点分隔键值对。
 * 例如：
 * <pre>
 * im:
 *   server:
 *     port: 8080
 * </pre>
 * 拍平为 {@code im.server.port → "8080"}。
 *
 * <p>支持 {@code classpath:} 前缀和文件路径两种方式。
 * 文件不存在时返回空配置（不抛异常），order 默认 2（与 {@link PropertyFileSource} 同级）。
 */
public class YamlConfigSource implements ConfigSource {

    private static final Logger log = LoggerFactory.getLogger(YamlConfigSource.class);

    private static final int DEFAULT_ORDER = 2;

    private final String path;
    private final int sourceOrder;
    private final Map<String, String> data;

    public YamlConfigSource(String path) {
        this(path, DEFAULT_ORDER);
    }

    public YamlConfigSource(String path, int order) {
        this.path = path;
        this.sourceOrder = order;
        var result = doLoad();
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

    @SuppressWarnings("unchecked")
    private LoadResult doLoad() {
        try (InputStream in = open(path)) {
            if (in == null) return new LoadResult(DEFAULT_ORDER, Map.of());

            Yaml yaml = new Yaml();
            Object raw = yaml.load(in);
            if (!(raw instanceof Map)) {
                log.warn("YAML root is not a map: {}", path);
                return new LoadResult(DEFAULT_ORDER, Map.of());
            }

            Map<String, String> flat = new LinkedHashMap<>();
            flatten0("", (Map<String, Object>) raw, flat);

            log.info("Loaded YAML config from {} (order={}, {} keys)", path, sourceOrder, flat.size());
            return new LoadResult(sourceOrder, Map.copyOf(flat));
        } catch (IOException e) {
            log.debug("Config file not found: {} ({})", path, e.getMessage());
            return new LoadResult(DEFAULT_ORDER, Map.of());
        }
    }

    private static InputStream open(String path) throws FileNotFoundException {
        if (path.startsWith("classpath:")) {
            String cpPath = path.substring("classpath:".length());
            InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(cpPath);
            if (in == null) {
                log.debug("Classpath resource not found: {}", cpPath);
            }
            return in;
        }
        // 文件路径
        return new FileInputStream(path);
    }

    /**
     * 将嵌套 Map 拍平为点分隔键。
     *
     * <pre>
     * {im: {server: {port: "8080"}}} → {"im.server.port": "8080"}
     * </pre>
     */
    @SuppressWarnings("unchecked")
    static Map<String, String> flatten(Map<String, Object> nested) {
        Map<String, String> result = new LinkedHashMap<>();
        flatten0("", nested, result);
        return result;
    }

    @SuppressWarnings("unchecked")
    private static void flatten0(String prefix, Map<String, Object> map, Map<String, String> out) {
        for (Map.Entry<String, Object> e : map.entrySet()) {
            String key = prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey();
            Object value = e.getValue();
            if (value instanceof Map) {
                flatten0(key, (Map<String, Object>) value, out);
            } else if (value instanceof List) {
                // 列表处理：逗号连接
                StringBuilder sb = new StringBuilder();
                for (Object item : (List<Object>) value) {
                    if (sb.length() > 0) sb.append(",");
                    sb.append(item);
                }
                out.put(key, sb.toString());
            } else if (value != null) {
                out.put(key, value.toString());
            }
        }
    }

    private record LoadResult(int order, Map<String, String> data) {}
}
