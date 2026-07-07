package com.im.common.exception;

import com.im.common.enums.ImErrorCode;

public class DatabasePersistenceException extends PersistenceException {
    public DatabasePersistenceException(String detail) {
        super(ImErrorCode.INTERNAL_ERROR, detail);
    }

    public DatabasePersistenceException(String detail, Throwable cause) {
        super(ImErrorCode.INTERNAL_ERROR, detail, cause);
    }
}
