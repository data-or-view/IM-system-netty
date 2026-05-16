package com.im.core.handler;

import com.im.api.content.ContentType;
import com.im.api.content.IMessageContent;

import java.util.Map;

/**
 * 消息内容解析器。
 *
 * <p>从 {@code _ct} 标识的内容类型解析消息体。
 * 支持从 ApiRequest params 或原始 bytes 中解析。</p>
 *
 * <p>纯函数工具类，无状态，可独立测试。</p>
 */
public final class ContentParser {

    public static final String CONTENT_TYPE_HEADER = "_ct";

    private ContentParser() {}

    /**
     * 从 ApiRequest params 解析消息内容。
     *
     * <p>优先从 params 的 "content" 字段（Map）直接反序列化（避免 bytes 中间态），
     * 回退到 bodyRaw bytes 反序列化（HTTP 场景）。</p>
     *
     * @param params  ApiRequest 的业务参数
     * @param bodyRaw 原始二进制载荷（HTTP 文件上传等场景）
     * @return 解析后的消息内容，若 {@code _ct} 不存在返回 {@code null}
     */
    public static IMessageContent parse(Map<String, Object> params, byte[] bodyRaw) {
        Object ctObj = params.get(CONTENT_TYPE_HEADER);
        if (ctObj == null) return null;

        ContentType ct = ContentType.valueOf(ctObj.toString().toUpperCase());
        IMessageContent content;

        // 优先从 params 的 content 字段反序列化（WS 场景：content 已经是 Map）
        Object contentObj = params.get("content");
        if (contentObj instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> contentMap = (Map<String, Object>) contentObj;
            content = ContentSerializer.fromMap(ct, contentMap);
        } else {
            // 回退到 bodyRaw bytes（HTTP 场景）
            content = ContentSerializer.fromBytes(ct, bodyRaw);
        }

        content.validate();
        return content;
    }
}
