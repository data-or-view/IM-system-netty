package com.im.common.exception;

import com.im.common.enums.ImErrorCode;

public class ConflictException extends BusinessException {
    public ConflictException(String detail) { super(ImErrorCode.CONFLICT, detail); }
    public ConflictException(String detail, Throwable cause) { super(ImErrorCode.CONFLICT, detail, cause); }
}
