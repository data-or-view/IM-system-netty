package com.im.api.content;

/**
 * 消息内容类型。
 * 与 CommandType 的差异：
 *   CommandType 定义「协议行为」（登录/心跳/聊天）
 *   ContentType  定义「消息内容格式」（文本/文件/图片）
 *
 * <p>每个枚举值绑定显式 int ID（非 ordinal），
 * 持久化到 DB 时使用 {@link #getId()}，
 * 避免因枚举声明顺序调整导致历史数据错位。</p>
 */
public enum ContentType {
    /** 纯文本消息 */
    TEXT(1),
    /** 文件传输 */
    FILE(2),
    /** 图片消息 */
    IMAGE(3),
    /** 系统通知（无 body，仅 headers） */
    SYSTEM(4),
    /** 音视频通话信令（通过 SFU 传输媒体，IM 管道仅做信令转发） */
    SIGNAL(5),
    /** 语音消息 */
    VOICE(6),
    /** 视频消息 */
    VIDEO(7),
    /** 位置消息 */
    LOCATION(8),
    /** @提及消息 */
    AT_TEXT(9),
    /** 引用回复 */
    QUOTE(10),
    /** 自定义消息（红包、名片等业务数据） */
    CUSTOM(11);

    private final int id;

    ContentType(int id) {
        this.id = id;
    }

    /**
     * 返回持久化用的显式 int ID。
     * 此 ID 在枚举生命周期内固定，不因声明顺序变化。
     */
    public int getId() {
        return id;
    }
}
