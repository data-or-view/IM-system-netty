package com.im.core.handler;

import com.im.api.IMCommand;
import com.im.api.content.ContentType;
import com.im.api.content.IMessageContent;
import com.im.core.handler.ContentSerializer;

/**
 * 消息内容解析器。
 *
 * <p>从 {@link IMCommand} 中提取 {@code _ct} header 标识的内容类型，
 * 反序列化消息体并校验内容合法性。</p>
 *
 * <p>纯函数工具类，无状态，可独立测试。</p>
 */
public final class ContentParser {

    public static final String CONTENT_TYPE_HEADER = "_ct";

    private ContentParser() {}

    /**
     * 解析消息内容。
     *
     * @param msg 原始消息
     * @return 解析后的消息内容，若 {@code _ct} header 不存在返回 {@code null}
     * @throws IllegalArgumentException 内容类型不支持或数据格式错误
     * @throws com.im.api.ImException  内容校验失败
     */
    public static IMessageContent parse(IMCommand msg) {
        String ctRaw = msg.getHeader(CONTENT_TYPE_HEADER);
        if (ctRaw == null) return null;

        ContentType ct = ContentType.valueOf(ctRaw.toUpperCase());
        IMessageContent content = ContentSerializer.fromBytes(ct, msg.getBody());
        content.validate();
        return content;
    }
}
