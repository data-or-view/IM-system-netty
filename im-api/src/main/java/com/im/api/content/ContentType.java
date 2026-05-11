package com.im.api.content;

/**
 * 消息内容类型。
 * 与 CommandType 的差异：
 *   CommandType 定义「协议行为」（登录/心跳/聊天）
 *   ContentType  定义「消息内容格式」（文本/文件/图片）
 */
public enum ContentType {
    /** 纯文本消息 */
    TEXT,
    /** 文件传输 */
    FILE,
    /** 图片消息 */
    IMAGE,
    /** 系统通知（无 body，仅 headers） */
    SYSTEM,
    /** 音视频通话信令（通过 SFU 传输媒体，IM 管道仅做信令转发） */
    SIGNAL
}
