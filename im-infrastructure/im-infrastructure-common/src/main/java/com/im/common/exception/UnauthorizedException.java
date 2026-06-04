package com.im.common.exception;

import com.im.common.enums.ImErrorCode;

public class UnauthorizedException extends BusinessException {
    public UnauthorizedException(String detail) { super(ImErrorCode.UNAUTHORIZED, detail); }
    public UnauthorizedException(String detail, Throwable cause) { super(ImErrorCode.UNAUTHORIZED, detail, cause); }
}
