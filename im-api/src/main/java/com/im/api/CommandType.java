package com.im.api;

/**
 * 消息类型枚举，对应 RocketMQ 的 RequestCode / ResponseCode。
 *
 * 每个类型用一个 short code 表示，wire protocol 中按 code 编码。
 * 大于 100 的 code 为系统保留。
 */
public enum CommandType {

    // ========== 系统 ==========
    /** 心跳（客户端→服务端） */
    HEARTBEAT(0),
    /** 心跳 ACK（服务端→客户端） */
    HEARTBEAT_ACK(1),

    // ========== 认证 ==========
    /** 登录请求 */
    LOGIN(10),
    /** 登录响应 */
    LOGIN_ACK(11),
    /** 登出 */
    LOGOUT(12),

    // ========== 消息 ==========
    /** 单聊消息 */
    SINGLE_CHAT(20),
    /** 单聊 ACK */
    SINGLE_CHAT_ACK(21),
    /** 群聊消息 */
    GROUP_CHAT(30),
    /** 群聊 ACK */
    GROUP_CHAT_ACK(31),
    /** 已读回执 */
    READ_ACK(40),
    /** 撤回消息 */
    REVOKE_MESSAGE(41),
    /** 撤回消息响应 */
    REVOKE_MESSAGE_ACK(42),

    // ========== 消息拉取 ==========
    /** 拉取消息请求 */
    PULL_MESSAGE(50),
    /** 拉取消息响应 */
    PULL_MESSAGE_ACK(51),

    // ========== 会话管理 ==========
    /** 获取会话列表 */
    CONVERSATION_GET(60),
    /** 获取会话列表响应 */
    CONVERSATION_GET_ACK(61),
    /** 更新会话设置（置顶/免打扰） */
    CONVERSATION_SET(62),
    /** 更新会话设置响应 */
    CONVERSATION_SET_ACK(63),

    // ========== 错误 ==========
    /** 通用错误 */
    ERROR(99);

    private final short code;

    CommandType(int code) {
        this.code = (short) code;
    }

    public short getCode() {
        return code;
    }

    /**
     * 根据 code 查找枚举。
     */
    public static CommandType fromCode(short code) {
        for (CommandType t : values()) {
            if (t.code == code) return t;
        }
        return ERROR;
    }
}
