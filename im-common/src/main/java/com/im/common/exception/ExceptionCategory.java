package com.im.common.exception;

/**
 * High-level exception category used by global handlers, metrics, and logging.
 */
public enum ExceptionCategory {
    BUSINESS,
    INFRASTRUCTURE,
    CONFIGURATION,
    SECURITY,
    DATA_CONSISTENCY
}
