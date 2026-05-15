package com.im.config;

import java.util.Map;

/**
 * 配置数据源接口。
 *
 * <p>每个数据源是一个配置来源，例如环境变量、系统属性、配置文件等。
 * {@link ConfigLoader} 启动时加载所有数据源，按 {@link #order()} 排序后合并。
 *
 * <p>order 规范：
 * <ul>
 *   <li>{@code 0} — 环境变量（最高优先级，覆盖其他所有）</li>
 *   <li>{@code 1} — 系统属性（-D 参数）</li>
 *   <li>{@code 2} — 配置文件（application.properties）</li>
 *   <li>{@code 3+} — 自定义数据源</li>
 * </ul>
 */
public interface ConfigSource {

    /**
     * 优先级顺序。值越小优先级越高，高优先级覆盖低优先级。
     */
    int order();

    /**
     * 加载该数据源的全部配置键值对。
     *
     * @return 不可变 map，不会为 null
     */
    Map<String, String> load();
}
