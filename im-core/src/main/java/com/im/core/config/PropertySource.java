package com.im.core.config;

import java.util.List;

/**
 * 配置源接口。
 *
 * <p>每个配置源实现按 {@link #order()} 排序，数值越小优先级越高。
 * <p>键统一使用 {@code im.} 前缀命名空间。
 *
 * <pre>
 *   PropertySources props = PropertySources.builder()
 *       .add(new SystemPropertySource())
 *       .add(new EnvPropertySource())
 *       .add(new YamlPropertySource("config/application.yml"))
 *       .add(new DefaultPropertySource(...))
 *       .build();
 *
 *   int port = props.getInt("im.ws.port", 8081);
 * </pre>
 */
public interface PropertySource {

    /**
     * 获取配置值，无此键则返回 {@code null}。
     */
    String get(String key);

    /**
     * 优先级，数值越小越优先。
     * <ul>
     *   <li>0-99: 系统属性（最高优先级）</li>
     *   <li>100-199: 环境变量</li>
     *   <li>200-299: 配置文件</li>
     *   <li>300+: 默认值</li>
     * </ul>
     */
    default int order() { return 300; }

    /**
     * 此源是否活跃（例如文件不存在时返回 {@code false}）。
     */
    default boolean isAvailable() { return true; }

    /**
     * 获取此源描述（用于日志）。
     */
    default String description() { return getClass().getSimpleName(); }
}
