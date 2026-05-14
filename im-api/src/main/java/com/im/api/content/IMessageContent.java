package com.im.api.content;

/**
 * 消息内容接口。
 * 每种消息类型（文本/文件/图片/系统通知）实现此接口。
 *
 * 职责：
 *   1. 声明自身的 ContentType
 *   2. 提供 validate() 校验方法
 *   3. 作为 Jackson 可序列化的 POJO（序列化由 ContentSerializer 在 im-core 层完成）
 */
public interface IMessageContent {

    /** 返回内容类型 */
    ContentType getContentType();

    /**
     * 校验内容是否合法。
     *
     * @throws IllegalArgumentException 如果内容不符合规范
     */
    void validate();
}
