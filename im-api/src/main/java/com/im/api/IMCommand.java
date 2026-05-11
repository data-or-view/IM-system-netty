package com.im.api;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 协议数据单元（DTO），对应 RocketMQ 的 RemotingCommand。
 *
 * 二进制帧结构（固定头 10 字节）：
 * ┌─────────┬──────────┬──────────┬──────────────┬──────────────┬────────────────┐
 * │  magic   │ version │  flags   │   bodyLen     │   headerLen   │   bodyContent  │
 * │  2 bytes │ 1 byte  │ 1 byte   │   4 bytes     │   2 bytes     │   bodyLen - hL  │
 * └─────────┴──────────┴──────────┴──────────────┴──────────────┴────────────────┘
 *
 * bodyLen = headerLen + bodyContent.length（固定头之后的总字节数）
 * headerLen = JSON headers 的字节数
 *
 * JSON headers 内嵌协议字段（_ 前缀）和用户自定义字段：
 *   {"_op":10, "_seq":1, "_mid":"uuid", "_ts":123, "fromUserId":"u1", ...}
 *
 * 这样解码器可以精确分帧：读 10 字节固定头 → 读 headerLen 字节 headers →
 * 读剩余 bodyLen - headerLen 字节作为 body，不会吞掉下一条消息。
 */
public class IMCommand {

    private static final AtomicInteger SEQ_GENERATOR = new AtomicInteger(1);

    /** 协议魔数 */
    public static final short MAGIC = (short) 0xACAC;

    /** 当前协议版本 */
    public static final byte CURRENT_VERSION = 1;

    /** Flags 常量：bit0 */
    public static final byte FLAG_REQUEST = 0;
    public static final byte FLAG_RESPONSE = 1;
    public static final byte FLAG_ONEWAY = 2;

    // ========== 固定头字段 ==========

    /** 消息类型 */
    private CommandType type;

    /** 协议版本 */
    private byte version = CURRENT_VERSION;

    /** 标志位：0=request, 1=response, 2=oneway */
    private byte flags;

    /** 序列号，用于请求-响应配对 */
    private int seqId;

    /** 消息唯一 ID（用于去重） */
    private String messageId;

    /** 时间戳 */
    private long timestamp;

    // ========== 可变字段 ==========

    /** 元数据头（用户自定义 KV） */
    private Map<String, String> headers;

    /** 消息体（编码后可省略 bodyLen 来计算） */
    private byte[] body;

    public IMCommand() {
        this.seqId = SEQ_GENERATOR.getAndIncrement();
        this.timestamp = System.currentTimeMillis();
        this.headers = new HashMap<>();
        this.body = new byte[0];
    }

    public IMCommand(CommandType type) {
        this();
        this.type = type;
    }

    // ========== Factory Methods ==========

    /**
     * 创建 ACK 响应，自动继承 seqId + messageId。
     */
    public IMCommand createAcknowledgement(CommandType ackType) {
        IMCommand ack = new IMCommand(ackType);
        ack.seqId = this.seqId;
        ack.messageId = this.messageId;
        ack.flags = FLAG_RESPONSE;
        return ack;
    }

    // ========== Header Helpers ==========

    public String getHeader(String key) {
        return headers != null ? headers.get(key) : null;
    }

    public void putHeader(String key, String value) {
        if (headers == null) {
            headers = new HashMap<>();
        }
        headers.put(key, value);
    }

    public Map<String, String> getHeaders() {
        return headers != null ? headers : Collections.emptyMap();
    }

    // ========== JSON Serialization ==========

    /**
     * 将所有协议字段 + 自定义 headers 合并为一个 flat JSON map。
     * 协议字段用 _ 前缀避免与用户 headers 冲突。
     */
    public Map<String, Object> toJsonMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("_op", (int) type.getCode());
        map.put("_seq", seqId);
        map.put("_mid", messageId);
        map.put("_ts", timestamp);
        map.put("_ver", (int) version);
        map.put("_flg", (int) flags);
        if (headers != null) {
            map.putAll(headers);
        }
        return map;
    }

    /**
     * 从 JSON map 回读协议字段 + 自定义 headers。
     */
    @SuppressWarnings("unchecked")
    public static IMCommand fromJsonMap(Map<String, Object> map) {
        IMCommand cmd = new IMCommand();

        // 复制到可变 map 以允许 remove，避免 Map.of() 等不可变入参出错
        Map<String, Object> mutable = new HashMap<>(map);

        Object opVal = mutable.remove("_op");
        if (opVal instanceof Number n) {
            cmd.type = CommandType.fromCode(n.shortValue());
        }

        Object seqVal = mutable.remove("_seq");
        if (seqVal instanceof Number n) {
            cmd.seqId = n.intValue();
        }

        Object midVal = mutable.remove("_mid");
        if (midVal instanceof String s) {
            cmd.messageId = s;
        }

        Object tsVal = mutable.remove("_ts");
        if (tsVal instanceof Number n) {
            cmd.timestamp = n.longValue();
        }

        Object verVal = mutable.remove("_ver");
        if (verVal instanceof Number n) {
            cmd.version = n.byteValue();
        }

        Object flgVal = mutable.remove("_flg");
        if (flgVal instanceof Number n) {
            cmd.flags = n.byteValue();
        }

        // 剩余字段都是用户自定义 headers
        Map<String, String> userHeaders = new HashMap<>();
        for (Map.Entry<String, Object> e : mutable.entrySet()) {
            if (e.getValue() != null) {
                userHeaders.put(e.getKey(), e.getValue().toString());
            }
        }
        cmd.headers = userHeaders;

        return cmd;
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

    // ========== Getters / Setters ==========

    public CommandType getType() { return type; }
    public void setType(CommandType type) { this.type = type; }

    public byte getVersion() { return version; }
    public void setVersion(byte version) { this.version = version; }

    public byte getFlags() { return flags; }
    public void setFlags(byte flags) { this.flags = flags; }
    public boolean isResponse() { return (flags & 1) == 1; }
    public boolean isOneway() { return (flags & 2) == 2; }

    public int getSeqId() { return seqId; }
    public void setSeqId(int seqId) { this.seqId = seqId; }

    public String getMessageId() { return messageId; }
    public void setMessageId(String messageId) { this.messageId = messageId; }

    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }

    public byte[] getBody() { return body; }
    public void setBody(byte[] body) { this.body = body; }

    // ========== equals / hashCode ==========

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof IMCommand command)) return false;
        return seqId == command.seqId && type == command.type;
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, seqId);
    }

    @Override
    public String toString() {
        return "IMCommand{type=" + type + ", seqId=" + seqId
                + ", messageId='" + messageId + '\'' + ", ts=" + timestamp
                + ", ver=" + version + ", flg=" + flags + '}';
    }
}
