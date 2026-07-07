package com.im.common.exception;

import com.im.common.enums.ImErrorCode;

public class NotFoundException extends BusinessException {
    public NotFoundException(String detail) { super(ImErrorCode.NOT_FOUND, detail); }
    public NotFoundException(String detail, Throwable cause) { super(ImErrorCode.NOT_FOUND, detail, cause); }
}
