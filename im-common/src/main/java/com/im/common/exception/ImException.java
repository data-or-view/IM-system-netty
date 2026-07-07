package com.im.common.exception;

import com.im.common.enums.ImErrorCode;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * IM 系统运行时异常，携带错误码。
 *
 * <p>全局捕获后自动转换为 ERROR 命令返回客户端。
 */
public class ImException extends RuntimeException {

    private final ImErrorCode errorCode;
    private final ExceptionCategory category;
    private final String detail;
    private final String safeMessage;
    private final boolean clientVisible;
    private final Map<String, Object> attributes = new LinkedHashMap<>();

    public ImException(ImErrorCode errorCode) {
        this(errorCode, null, null);
    }

    public ImException(ImErrorCode errorCode, String detail) {
        this(errorCode, detail, null);
    }

    public ImException(ImErrorCode errorCode, String detail, Throwable cause) {
        this(errorCode, detail, cause, ExceptionCategory.BUSINESS, detail, true);
    }

    protected ImException(ImErrorCode errorCode, String detail, Throwable cause,
                          ExceptionCategory category, String safeMessage, boolean clientVisible) {
        super(formatMessage(errorCode, detail), cause);
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode");
        this.category = Objects.requireNonNull(category, "category");
        this.detail = detail;
        this.safeMessage = safeMessage != null ? safeMessage : errorCode.getMessage();
        this.clientVisible = clientVisible;
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

    public ExceptionCategory getCategory() {
        return category;
    }

    public String getSafeMessage() {
        return safeMessage;
    }

    public boolean isClientVisible() {
        return clientVisible;
    }

    public Map<String, Object> getAttributes() {
        return Collections.unmodifiableMap(attributes);
    }

    @SuppressWarnings("unchecked")
    public <T extends ImException> T withAttribute(String key, Object value) {
        if (key != null && !key.isBlank() && value != null) {
            attributes.put(key, value);
        }
        return (T) this;
    }

    private static String formatMessage(ImErrorCode errorCode, String detail) {
        if (detail != null && !detail.isEmpty()) {
            return errorCode.getCode() + " " + errorCode.getMessage() + ": " + detail;
        }
        return errorCode.getCode() + " " + errorCode.getMessage();
    }
}
