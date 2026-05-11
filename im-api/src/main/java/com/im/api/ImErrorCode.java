package com.im.api;

/**
 * IM 系统错误码，参考 HTTP 状态码分类。
 *
 * 使用方式：
 *   throw new ImException(ImErrorCode.UNAUTHORIZED, "token expired");
 *
 * 全局异常处理（MessageRouterHandler 中）：
 *   捕获 ImException → 提取 code + message → 返回 ERROR 命令
 *   捕获其他 Exception → 返回 ImErrorCode.INTERNAL_ERROR
 */
public enum ImErrorCode {

    // ── 成功 ──
    OK(0, "ok"),

    // ── 4xx 客户端错误 ──
    BAD_REQUEST(400, "bad request"),
    UNAUTHORIZED(401, "unauthorized"),
    FORBIDDEN(403, "forbidden"),
    /** 资源冲突（用户已存在、群已存在等） */
    CONFLICT(409, "conflict"),
    NOT_FOUND(404, "not found"),
    RATE_LIMITED(429, "rate limited"),

    /** 消息内容格式错误（ContentType 无法解析 / validate 失败） */
    INVALID_MESSAGE(440, "invalid message content"),

    /** 用户不在线 */
    USER_OFFLINE(480, "user offline"),

    /** 消息投递失败 */
    DELIVERY_FAILED(481, "delivery failed"),

    /** 消息体超过限制 */
    MESSAGE_TOO_LARGE(482, "message too large"),

    /** 消息重复（重复的 messageId） */
    DUPLICATE_MESSAGE(483, "duplicate message"),

    // ── 5xx 服务端错误 ──
    /** 未预期的内部错误 */
    INTERNAL_ERROR(500, "internal server error"),

    /** MQ 不可用 */
    MQ_UNAVAILABLE(503, "message queue unavailable"),

    /** 集群转发失败 */
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
