package com.im.config;

import com.im.common.exception.ConfigurationException;

/**
 * 配置异常。
 *
 * <p>必需键缺失、类型转换失败时抛出。
 */
public class ConfigException extends ConfigurationException {

    public ConfigException(String message) {
        super(message);
    }

    public ConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
