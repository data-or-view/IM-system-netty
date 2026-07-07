package com.im.common.exception;

import com.im.common.enums.ImErrorCode;

public class RedisPersistenceException extends PersistenceException {
    public RedisPersistenceException(String detail) {
        super(ImErrorCode.INTERNAL_ERROR, detail);
    }

    public RedisPersistenceException(String detail, Throwable cause) {
        super(ImErrorCode.INTERNAL_ERROR, detail, cause);
    }
}
