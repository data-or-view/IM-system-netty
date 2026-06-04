package com.im.common.exception;

import com.im.common.enums.ImErrorCode;

public class ValidationException extends BusinessException {
    public ValidationException(String detail) { super(ImErrorCode.BAD_REQUEST, detail); }
    public ValidationException(String detail, Throwable cause) { super(ImErrorCode.BAD_REQUEST, detail, cause); }
}
