package com.im.common.exception;

import com.im.common.enums.ImErrorCode;

/**
 * Server-side infrastructure exception. Details are not exposed to clients by default.
 */
public class InfrastructureException extends ImException {

    public InfrastructureException(ImErrorCode errorCode, String detail) {
        this(errorCode, detail, null);
    }

    public InfrastructureException(ImErrorCode errorCode, String detail, Throwable cause) {
        super(errorCode, detail, cause, ExceptionCategory.INFRASTRUCTURE,
                errorCode.getMessage(), false);
    }
}
