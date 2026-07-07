package com.im.common.exception;

import com.im.common.enums.ImErrorCode;

/**
 * Base exception for persistence failures such as database, Redis, and object storage errors.
 */
public class PersistenceException extends InfrastructureException {
    public PersistenceException(ImErrorCode errorCode, String detail) {
        super(errorCode, detail);
    }

    public PersistenceException(ImErrorCode errorCode, String detail, Throwable cause) {
        super(errorCode, detail, cause);
    }
}
