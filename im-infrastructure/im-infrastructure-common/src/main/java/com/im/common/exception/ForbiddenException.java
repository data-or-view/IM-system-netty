package com.im.common.exception;

import com.im.common.enums.ImErrorCode;

public class ForbiddenException extends BusinessException {
    public ForbiddenException(String detail) { super(ImErrorCode.FORBIDDEN, detail); }
    public ForbiddenException(String detail, Throwable cause) { super(ImErrorCode.FORBIDDEN, detail, cause); }
}
