package com.im.core.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.*;

/**
 * YAML 文件配置源。
 *
 * <p>解析 YAML 文件并拍平为 {@code im.server.port → "8080"} 格式的键值对。
 * <p>支持 {@code classpath:} 和文件路径两种方式。
 * <p>优先级 200。
 *
 * <pre>
 * new YamlPropertySource("config/application.yml")
 * new YamlPropertySource("classpath:application.yml")
 * </pre>
 */
public class YamlPropertySource extends MapBackedPropertySource {

    private static final Logger log = LoggerFactory.getLogger(YamlPropertySource.class);

    private final String path;
    private final boolean available;

    public YamlPropertySource(String path) {
        super(flatten(load(path)));
        this.path = path;
        this.available = !entries.isEmpty();
    }

    @Override
    public int order() { return 200; }

    @Override
    public boolean isAvailable() { return available; }

    @Override
    public String description() { return "YAML: " + path; }

    // ── 加载 ──

    @SuppressWarnings("unchecked")
    private static Map<String, Object> load(String path) {
        try (InputStream in = open(path)) {
            if (in == null) return Map.of();
            Yaml yaml = new Yaml();
            Object raw = yaml.load(in);
            if (raw instanceof Map) {
                return (Map<String, Object>) raw;
            }
            log.warn("YAML root is not a map: {}", path);
            return Map.of();
        } catch (Exception e) {
            log.warn("Failed to load YAML: {} ({})", path, e.getMessage());
            return Map.of();
        }
    }

    private static InputStream open(String path) throws FileNotFoundException {
        if (path.startsWith("classpath:")) {
            String cpPath = path.substring("classpath:".length());
            InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(cpPath);
            if (in == null) {
                log.debug("Classpath resource not found: {}", cpPath);
                return null;
            }
            return in;
        }
        // 文件路径
        try {
            return new FileInputStream(path);
        } catch (FileNotFoundException e) {
            log.debug("Config file not found: {}", path);
            return null;
        }
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
    private static void flatten0(String prefix, Object value, Map<String, String> out) {
        if (value instanceof Map) {
            for (Map.Entry<String, Object> e : ((Map<String, Object>) value).entrySet()) {
                String key = prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey();
                flatten0(key, e.getValue(), out);
            }
        } else if (value instanceof List) {
            // 列表处理：逗号连接
            StringBuilder sb = new StringBuilder();
            for (Object item : (List<Object>) value) {
                if (sb.length() > 0) sb.append(",");
                sb.append(item);
            }
            out.put(prefix, sb.toString());
        } else if (value != null) {
            out.put(prefix, value.toString());
        }
    }
}
