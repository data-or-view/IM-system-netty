package com.im.core.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.im.api.content.*;
import com.im.core.serialization.jackson.ObjectMapperProvider;

import java.util.Map;

/**
 * 消息内容序列化/反序列化。
 * 将 IMessageContent 实例 ↔ JSON bytes 互相转换。
 *
 * 序列化约定（与 OpenIM 对齐）：
 *   TEXT    → {"text":"..."}
 *   FILE    → {"uuid":"...","fileName":"...","fileSize":123,"url":"..."}
 *   IMAGE   → {"sourcePicture":{...},"bigPicture":{...},"snapshotPicture":{...}}
 *   SYSTEM  → {"systemType":"...","message":"..."}
 *   SIGNAL  → {"_act":1,"_room":"...","_token":"..."}
 *   VOICE   → {"uuid":"...","url":"...","fileSize":123,"duration":30}
 *   VIDEO   → {"videoUrl":"...","videoUuid":"...","videoType":"...","videoSize":123,"duration":120,"snapshotUrl":"...","snapshotWidth":640,"snapshotHeight":480,"snapshotSize":23456}
 *   LOCATION → {"description":"...","longitude":116.46,"latitude":39.92}
 *   AT_TEXT → {"text":"@...","atUserList":["user1"]}
 *   QUOTE   → {"text":"...","quotedMessageId":"...","quotedSenderId":"...","quotedContent":"..."}
 *   CUSTOM  → {"data":"...","description":"...","extension":"..."}
 */
public class ContentSerializer {

    private static final ObjectMapper MAPPER = ObjectMapperProvider.get();

    private ContentSerializer() {}

    /**
     * 将消息内容序列化为 JSON 字节数组。
     * SYSTEM 消息也写入 body，因为当前 Message 模型没有独立 headers 字段；
     * 如果继续丢弃 body，Web 端只能看到空白系统消息。
     */
    public static byte[] toBytes(IMessageContent content) {
        if (content == null) {
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
            case VOICE -> VoiceContent.class;
            case VIDEO -> VideoContent.class;
            case LOCATION -> LocationContent.class;
            case AT_TEXT -> AtTextContent.class;
            case QUOTE -> QuoteContent.class;
            case CUSTOM -> CustomContent.class;
        };
    }
}
