package com.im.api.content;

import java.util.Objects;
import java.util.Map;

/**
 * 系统通知内容。
 * 无 body，所有信息通过 headers 传递（如通知类型、目标用户、变更事件等）。
 *
 * 示例 headers：
 *   _sys_type → "user_online" / "group_created" / "kicked"
 *   _sys_msg  → "用户 xxx 已上线"
 */
public class SystemContent implements IMessageContent {

    /** 系统通知类型（存储在 IMCommand.headers 的 _sys_type 字段） */
    private String systemType;

    /** 通知描述（存储在 IMCommand.headers 的 _sys_msg 字段） */
    private String message;

    /** Jackson 反序列化用 */
    public SystemContent() {}

    public SystemContent(String systemType, String message) {
        this.systemType = systemType;
        this.message = message;
    }

    /**
     * 从 IMCommand 的 headers 构造系统通知。
     * 系统消息的信息全部在 headers 中，body 为空。
     */
    public static SystemContent fromHeaders(Map<String, String> headers) {
        return new SystemContent(
                headers != null ? headers.get("_sys_type") : null,
                headers != null ? headers.get("_sys_msg") : null
        );
    }

    public String getSystemType() { return systemType; }
    public void setSystemType(String systemType) { this.systemType = systemType; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    @Override
    public ContentType getContentType() { return ContentType.SYSTEM; }

    @Override
    public void validate() {
        if (systemType == null || systemType.isBlank()) {
            throw new IllegalArgumentException("system type must not be null or blank");
        }
        // message 是可选的
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SystemContent that)) return false;
        return Objects.equals(systemType, that.systemType)
                && Objects.equals(message, that.message);
    }

    @Override
    public int hashCode() { return Objects.hash(systemType, message); }

    @Override
    public String toString() {
        return "SystemContent{type='" + systemType + "', msg='" + message + "'}";
    }
}
