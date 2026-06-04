package com.im.common.exception;

import com.im.common.enums.ImErrorCode;

/**
 * Configuration loading or validation failure.
 */
public class ConfigurationException extends InfrastructureException {

    public ConfigurationException(String detail) {
        super(ImErrorCode.INTERNAL_ERROR, detail);
    }

    public ConfigurationException(String detail, Throwable cause) {
        super(ImErrorCode.INTERNAL_ERROR, detail, cause);
    }

    @Override
    public ExceptionCategory getCategory() {
        return ExceptionCategory.CONFIGURATION;
    }
}
