package com.im.config;

/**
 * 配置异常。
 *
 * <p>必需键缺失、类型转换失败时抛出。
 */
public class ConfigException extends RuntimeException {

    public ConfigException(String message) {
        super(message);
    }

    public ConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
