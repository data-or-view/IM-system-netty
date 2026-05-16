package com.im.api;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 持久化层消息对象。
 *
 * <p>替换 {@link IMCommand} 在消息存储、队列、投递链路中的角色。
 * 纯数据类，不包含传输协议字段（CommandType、version、flags 等）。</p>
 *
 * <p>序列化使用 {@link #toJsonMap()} / {@link #fromJsonMap(Map)}，
 * 与 RedisMessageQueue 和 RedisClusterMessageBus 配合使用。</p>
 */
public class Message {

    /** 消息唯一 ID */
    private String messageId;

    /** 请求-响应配对 seq（原 IMCommand.seqId） */
    private long sequenceId;

    /** 消息时间戳 */
    private long timestamp;

    /** 发送者 */
    private String fromUserId;

    /** 接收者（单聊） */
    private String toUserId;

    /** 群 ID（群聊） */
    private String groupId;

    /** 会话 ID */
    private String conversationId;

    /** 内容类型代码 */
    private int contentType;

    /** 内容 JSON 字符串 */
    private String content;

    /** 会话内消息序号（原 _ms 头） */
    private long messageSeq;

    /** 原始二进制（文件上传等） */
    private byte[] body;

    /** 消息状态 */
    private int status;

    /** 扩展元数据（senderNickname、senderFaceUrl 等） */
    private Map<String, String> metadata;

    public Message() {
        this.timestamp = System.currentTimeMillis();
        this.metadata = new HashMap<>();
    }

    // ========== 工厂方法 ==========

    /**
     * 创建单聊消息。
     */
    public static Message createSingle(String fromUserId, String toUserId, String conversationId,
                                       int contentType, String content, long messageSeq) {
        Message msg = new Message();
        msg.fromUserId = fromUserId;
        msg.toUserId = toUserId;
        msg.conversationId = conversationId;
        msg.contentType = contentType;
        msg.content = content;
        msg.messageSeq = messageSeq;
        return msg;
    }

    /**
     * 创建群聊消息。
     */
    public static Message createGroup(String fromUserId, String groupId, String conversationId,
                                      int contentType, String content, long messageSeq) {
        Message msg = new Message();
        msg.fromUserId = fromUserId;
        msg.groupId = groupId;
        msg.conversationId = conversationId;
        msg.contentType = contentType;
        msg.content = content;
        msg.messageSeq = messageSeq;
        return msg;
    }

    /**
     * 为群聊中的某个成员复制消息。
     * 设置 toUserId，保留其他字段。
     */
    public Message copyForUser(String userId) {
        Message copy = new Message();
        copy.messageId = this.messageId;
        copy.sequenceId = this.sequenceId;
        copy.timestamp = this.timestamp;
        copy.fromUserId = this.fromUserId;
        copy.groupId = this.groupId;
        copy.conversationId = this.conversationId;
        copy.contentType = this.contentType;
        copy.content = this.content;
        copy.messageSeq = this.messageSeq;
        copy.body = this.body != null ? this.body.clone() : null;
        copy.status = this.status;
        copy.metadata = this.metadata != null ? new HashMap<>(this.metadata) : new HashMap<>();
        copy.toUserId = userId;
        return copy;
    }

    // ========== JSON Serialization ==========

    /**
     * 转换为 JSON map（用于 RedisMessageQueue / RedisClusterMessageBus 序列化）。
     *
     * 协议字段用 _ 前缀：
     *   _mid, _seq, _ts, _ms, _st
     *
     * 业务字段直接暴露：
     *   fromUserId, toUserId, groupId, conversationId, contentType, content
     *
     * 元数据展开为 flat 字段。
     */
    public Map<String, Object> toJsonMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("_mid", messageId);
        map.put("_seq", sequenceId);
        map.put("_ts", timestamp);
        map.put("_ms", messageSeq);
        map.put("_st", status);
        putIfNotNull(map, "fromUserId", fromUserId);
        putIfNotNull(map, "toUserId", toUserId);
        putIfNotNull(map, "groupId", groupId);
        putIfNotNull(map, "conversationId", conversationId);
        map.put("contentType", contentType);
        putIfNotNull(map, "content", content);

        // _body（Base64 编码的原始二进制，文件上传等场景）
        if (body != null && body.length > 0) {
            map.put("_body", Base64.getEncoder().encodeToString(body));
        }

        if (metadata != null) {
            map.putAll(metadata);
        }
        return map;
    }

    /**
     * 从 JSON map 回读 Message。
     */
    @SuppressWarnings("unchecked")
    public static Message fromJsonMap(Map<String, Object> map) {
        Message msg = new Message();
        Map<String, Object> mutable = new HashMap<>(map);

        Object midVal = mutable.remove("_mid");
        if (midVal instanceof String s) msg.messageId = s;

        Object seqVal = mutable.remove("_seq");
        if (seqVal instanceof Number n) msg.sequenceId = n.longValue();

        Object tsVal = mutable.remove("_ts");
        if (tsVal instanceof Number n) msg.timestamp = n.longValue();

        Object msVal = mutable.remove("_ms");
        if (msVal instanceof Number n) msg.messageSeq = n.longValue();

        Object stVal = mutable.remove("_st");
        if (stVal instanceof Number n) msg.status = n.intValue();

        Object fuVal = mutable.remove("fromUserId");
        if (fuVal instanceof String s) msg.fromUserId = s;

        Object tuVal = mutable.remove("toUserId");
        if (tuVal instanceof String s) msg.toUserId = s;

        Object gidVal = mutable.remove("groupId");
        if (gidVal instanceof String s) msg.groupId = s;

        Object cidVal = mutable.remove("conversationId");
        if (cidVal instanceof String s) msg.conversationId = s;

        Object ctVal = mutable.remove("contentType");
        if (ctVal instanceof Number n) msg.contentType = n.intValue();

        Object cntVal = mutable.remove("content");
        if (cntVal instanceof String s) msg.content = s;

        // _body（Base64 编码的原始二进制）
        Object bodyB64 = mutable.remove("_body");
        if (bodyB64 instanceof String s && !s.isEmpty()) {
            msg.body = Base64.getDecoder().decode(s);
        }

        // 剩余字段为元数据
        Map<String, String> meta = new HashMap<>();
        for (Map.Entry<String, Object> e : mutable.entrySet()) {
            if (e.getValue() != null) {
                meta.put(e.getKey(), e.getValue().toString());
            }
        }
        msg.metadata = meta;

        return msg;
    }

    // ========== Body Helpers ==========

    public void setBodyString(String content) {
        this.body = content != null ? content.getBytes(StandardCharsets.UTF_8) : new byte[0];
    }

    public String getBodyString() {
        return body != null && body.length > 0
                ? new String(body, StandardCharsets.UTF_8)
                : "";
    }

    // ========== Metadata Helpers ==========

    public String getMeta(String key) {
        return metadata != null ? metadata.get(key) : null;
    }

    public void putMeta(String key, String value) {
        if (metadata == null) metadata = new HashMap<>();
        metadata.put(key, value);
    }

    // ========== Getters / Setters ==========

    public String getMessageId() { return messageId; }
    public void setMessageId(String messageId) { this.messageId = messageId; }

    public long getSequenceId() { return sequenceId; }
    public void setSequenceId(long sequenceId) { this.sequenceId = sequenceId; }

    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }

    public String getFromUserId() { return fromUserId; }
    public void setFromUserId(String fromUserId) { this.fromUserId = fromUserId; }

    public String getToUserId() { return toUserId; }
    public void setToUserId(String toUserId) { this.toUserId = toUserId; }

    public String getGroupId() { return groupId; }
    public void setGroupId(String groupId) { this.groupId = groupId; }

    public String getConversationId() { return conversationId; }
    public void setConversationId(String conversationId) { this.conversationId = conversationId; }

    public int getContentType() { return contentType; }
    public void setContentType(int contentType) { this.contentType = contentType; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public long getMessageSeq() { return messageSeq; }
    public void setMessageSeq(long messageSeq) { this.messageSeq = messageSeq; }

    public byte[] getBody() { return body; }
    public void setBody(byte[] body) { this.body = body; }

    public int getStatus() { return status; }
    public void setStatus(int status) { this.status = status; }

    public Map<String, String> getMetadata() { return metadata; }
    public void setMetadata(Map<String, String> metadata) { this.metadata = metadata; }

    // ========== equals / hashCode / toString ==========

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Message message)) return false;
        return Objects.equals(messageId, message.messageId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(messageId);
    }

    @Override
    public String toString() {
        return "Message{mid='" + messageId + "', seq=" + sequenceId
                + ", from=" + fromUserId + ", to=" + toUserId
                + ", group=" + groupId + ", conv=" + conversationId
                + ", type=" + contentType + ", ms=" + messageSeq + '}';
    }

    // ========== Internal ==========

    private static void putIfNotNull(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }
}
