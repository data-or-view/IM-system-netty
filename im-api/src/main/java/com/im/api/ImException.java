package com.im.api;

import java.util.Objects;

/**
 * IM 系统运行时异常，携带错误码。
 *
 * 在 MessageRouterHandler 中全局捕获，自动转换为 ERROR 命令返回客户端。
 *
 * 用法：
 * <pre>
 * // 简单错误
 * throw new ImException(ImErrorCode.UNAUTHORIZED);
 *
 * // 带详细原因的
 * throw new ImException(ImErrorCode.INVALID_MESSAGE, "content type not supported: " + ct);
 *
 * // 包装底层异常
 * throw new ImException(ImErrorCode.INTERNAL_ERROR, "redis unavailable", cause);
 * </pre>
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
