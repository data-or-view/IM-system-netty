package com.im.core.config;

/**
 * 系统属性配置源。
 *
 * <p>读取 {@code System.getProperty(key)}。键直接以 {@code im.} 前缀传入。
 * <p>优先级 0（最高），对应 {@code -Dim.ws.port=8081} 等 JVM 参数。
 */
public class SystemPropertySource implements PropertySource {

    @Override
    public String get(String key) {
        return System.getProperty(key);
    }

    @Override
    public int order() { return 0; }

    @Override
    public String description() { return "System Properties (-D)"; }
}
