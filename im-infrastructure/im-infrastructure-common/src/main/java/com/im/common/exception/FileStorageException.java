package com.im.common.exception;

import com.im.common.enums.ImErrorCode;

public class FileStorageException extends PersistenceException {
    public FileStorageException(String detail) {
        super(ImErrorCode.INTERNAL_ERROR, detail);
    }

    public FileStorageException(String detail, Throwable cause) {
        super(ImErrorCode.INTERNAL_ERROR, detail, cause);
    }
}
