package com.im.common.exception;

import com.im.common.enums.ImErrorCode;

/**
 * Client-visible business/API exception.
 */
public class BusinessException extends ImException {

    public BusinessException(ImErrorCode errorCode) {
        super(errorCode, null, null, ExceptionCategory.BUSINESS, null, true);
    }

    public BusinessException(ImErrorCode errorCode, String detail) {
        super(errorCode, detail, null, ExceptionCategory.BUSINESS, detail, true);
    }

    public BusinessException(ImErrorCode errorCode, String detail, Throwable cause) {
        super(errorCode, detail, cause, ExceptionCategory.BUSINESS, detail, true);
    }
}
