package com.im.core.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.im.api.content.*;
import com.im.core.serialization.jackson.ObjectMapperProvider;

import java.util.Map;

/**
 * 消息内容序列化/反序列化。
 * 将 IMessageContent 实例 ↔ JSON bytes 互相转换。
 *
 * 序列化约定：
 *   TEXT   → {"text":"..."}
 *   FILE   → {"fileName":"...","fileSize":123,"url":"..."}
 *   IMAGE  → {"width":800,"height":600,"format":"png","fileSize":12345,"url":"..."}
 *   SYSTEM → 空（body 为空，信息存 IMCommand.headers 的 _sys_type / _sys_msg）
 */
public class ContentSerializer {

    private static final ObjectMapper MAPPER = ObjectMapperProvider.get();

    private ContentSerializer() {}

    /**
     * 将消息内容序列化为 JSON 字节数组。
     * SYSTEM 类型返回空数组（无 body）。
     */
    public static byte[] toBytes(IMessageContent content) {
        if (content == null || content.getContentType() == ContentType.SYSTEM) {
            return new byte[0];
        }
        try {
            return MAPPER.writeValueAsBytes(content);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize content: " + content, e);
        }
    }

    /**
     * 根据 ContentType 将 JSON 字节数组反序列化为消息内容。
     *
     * @param contentType 内容类型
     * @param body        JSON 字节数组（SYSTEM 可为空）
     * @return 反序列化后的消息内容
     */
    public static IMessageContent fromBytes(ContentType contentType, byte[] body) {
        if (contentType == ContentType.SYSTEM) {
            return new SystemContent(null, null);
        }
        if (body == null || body.length == 0) {
            throw new IllegalArgumentException("body must not be empty for content type: " + contentType);
        }
        try {
            Class<? extends IMessageContent> clazz = getImplClass(contentType);
            return MAPPER.readValue(body, clazz);
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize content: type=" + contentType, e);
        }
    }

    /**
     * 直接从 Map 反序列化为消息内容（无需 bytes 中间态）。
     * 用于 WS 场景：JSON 帧中的 content 字段已经是 Map，直接 convertValue。
     */
    @SuppressWarnings("unchecked")
    public static IMessageContent fromMap(ContentType contentType, Map<String, Object> map) {
        if (contentType == ContentType.SYSTEM) {
            return new SystemContent(null, null);
        }
        if (map == null || map.isEmpty()) {
            throw new IllegalArgumentException("content map must not be empty for type: " + contentType);
        }
        try {
            Class<? extends IMessageContent> clazz = getImplClass(contentType);
            return MAPPER.convertValue(map, clazz);
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize content from map: type=" + contentType, e);
        }
    }

    private static Class<? extends IMessageContent> getImplClass(ContentType type) {
        return switch (type) {
            case TEXT -> TextContent.class;
            case FILE -> FileContent.class;
            case IMAGE -> ImageContent.class;
            case SYSTEM -> SystemContent.class;
            case SIGNAL -> SignalingContent.class;
        };
    }
}
