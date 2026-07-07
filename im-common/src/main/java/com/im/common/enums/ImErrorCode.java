package com.im.common.enums;

/**
 * IM 系统错误码，参考 HTTP 状态码分类。
 */
public enum ImErrorCode {

    // ── 成功 ──
    OK(0, "ok"),

    // ── 4xx 客户端错误 ──
    BAD_REQUEST(400, "bad request"),
    UNAUTHORIZED(401, "unauthorized"),
    FORBIDDEN(403, "forbidden"),
    CONFLICT(409, "conflict"),
    NOT_FOUND(404, "not found"),
    RATE_LIMITED(429, "rate limited"),

    INVALID_MESSAGE(440, "invalid message content"),
    USER_OFFLINE(480, "user offline"),
    DELIVERY_FAILED(481, "delivery failed"),
    MESSAGE_TOO_LARGE(482, "message too large"),
    DUPLICATE_MESSAGE(483, "duplicate message"),

    // ── 5xx 服务端错误 ──
    INTERNAL_ERROR(500, "internal server error"),
    MQ_UNAVAILABLE(503, "message queue unavailable"),
    CLUSTER_FORWARD_FAILED(504, "cluster forward failed");

    private final int code;
    private final String message;

    ImErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    @Override
    public String toString() {
        return code + " " + message;
    }
}
