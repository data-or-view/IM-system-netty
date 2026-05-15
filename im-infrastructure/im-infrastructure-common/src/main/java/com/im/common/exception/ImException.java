package com.im.common.exception;

import com.im.common.enums.ImErrorCode;
import java.util.Objects;

/**
 * IM 系统运行时异常，携带错误码。
 *
 * <p>全局捕获后自动转换为 ERROR 命令返回客户端。
 */
public class ImException extends RuntimeException {

    private final ImErrorCode errorCode;
    private final String detail;

    public ImException(ImErrorCode errorCode) {
        this(errorCode, null, null);
    }

    public ImException(ImErrorCode errorCode, String detail) {
        this(errorCode, detail, null);
    }

    public ImException(ImErrorCode errorCode, String detail, Throwable cause) {
        super(formatMessage(errorCode, detail), cause);
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode");
        this.detail = detail;
    }

    public ImErrorCode getErrorCode() {
        return errorCode;
    }

    public int getCode() {
        return errorCode.getCode();
    }

    public String getDetail() {
        return detail;
    }

    private static String formatMessage(ImErrorCode errorCode, String detail) {
        if (detail != null && !detail.isEmpty()) {
            return errorCode.getCode() + " " + errorCode.getMessage() + ": " + detail;
        }
        return errorCode.getCode() + " " + errorCode.getMessage();
    }
}
