package com.im.api;

/**
 * 消息类型枚举，对应 RocketMQ 的 RequestCode / ResponseCode。
 *
 * 每个类型用一个 short code 表示，wire protocol 中按 code 编码。
 * 大于 100 的 code 为系统保留。
 */
public enum CommandType {

    // ========== 系统 (0-9) ==========
    /** 心跳（客户端→服务端） */
    HEARTBEAT(0),
    /** 心跳 ACK（服务端→客户端） */
    HEARTBEAT_ACK(1),

    // ========== 认证 (10-19) ==========
    /** 登录请求 */
    LOGIN(10),
    /** 登录响应 */
    LOGIN_ACK(11),
    /** 登出 */
    LOGOUT(12),
    /** 注册请求 */
    REGISTER(14),
    /** 注册响应 */
    REGISTER_ACK(15),

    // ========== 消息 (20-49) ==========
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
    /** 拉取消息 */
    PULL_MESSAGE(50),
    /** 拉取消息响应 */
    PULL_MESSAGE_ACK(51),

    // ========== 会话管理 (60-69) ==========
    /** 获取会话列表 */
    CONVERSATION_GET(60),
    /** 获取会话列表响应 */
    CONVERSATION_GET_ACK(61),
    /** 更新会话设置（置顶/免打扰） */
    CONVERSATION_SET(62),
    /** 更新会话设置响应 */
    CONVERSATION_SET_ACK(63),

    // ========== 好友 (70-79) ==========
    /** 申请添加好友 */
    FRIEND_APPLY(70),
    /** 申请添加好友响应 */
    FRIEND_APPLY_ACK(71),
    /** 处理好友申请 */
    FRIEND_APPROVE(72),
    /** 处理好友申请响应 */
    FRIEND_APPROVE_ACK(73),
    /** 删除好友 */
    FRIEND_REMOVE(74),
    /** 删除好友响应 */
    FRIEND_REMOVE_ACK(75),
    /** 好友列表 */
    FRIEND_LIST(76),
    /** 好友列表响应 */
    FRIEND_LIST_ACK(77),
    /** 拉黑 */
    BLACK_ADD(78),
    /** 移除黑名单 */
    BLACK_REMOVE(79),

    // ========== 群组 (80-89) ==========
    /** 创建群组 */
    GROUP_CREATE(80),
    /** 创建群组响应 */
    GROUP_CREATE_ACK(81),
    /** 申请加群 */
    GROUP_JOIN(82),
    /** 申请加群响应 */
    GROUP_JOIN_ACK(83),
    /** 退群 */
    GROUP_QUIT(84),
    /** 退群响应 */
    GROUP_QUIT_ACK(85),
    /** 踢人 */
    GROUP_KICK(86),
    /** 踢人响应 */
    GROUP_KICK_ACK(87),
    /** 修改群信息 */
    GROUP_INFO_UPDATE(88),
    /** 修改群信息响应 */
    GROUP_INFO_UPDATE_ACK(89),

    // ========== 用户 (90-99) ==========
    /** 更新用户资料 */
    USER_UPDATE(90),
    /** 更新用户资料响应 */
    USER_UPDATE_ACK(91),

    // ========== 群组搜索 (94-95) ==========
    /** 搜索群组 */
    GROUP_SEARCH(94),
    /** 搜索群组响应 */
    GROUP_SEARCH_ACK(95),

    // ========== 用户搜索 (92-93) ==========
    /** 搜索用户 */
    USER_SEARCH(92),
    /** 搜索用户响应 */
    USER_SEARCH_ACK(93),

    // ========== 文件传输 (100-109) ==========
    /** 上传文件 */
    FILE_UPLOAD(100),
    /** 上传文件响应 */
    FILE_UPLOAD_ACK(101),
    /** 下载文件 */
    FILE_DOWNLOAD(102),
    /** 下载文件响应 */
    FILE_DOWNLOAD_ACK(103),

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
