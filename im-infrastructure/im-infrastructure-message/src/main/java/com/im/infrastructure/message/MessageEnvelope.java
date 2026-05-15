package com.im.infrastructure.message;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 统一消息信封。
 *
 * <p>中间件自身的消息模型，业务负载统一使用 byte[]，
 * 由 {@link MessageCodec} 负责对象与字节之间的编解码。
 *
 * <p>设计参考 cinema-message-middleware 的 MessageEnvelope：
 * <ul>
 *   <li>channel 映射到底层 MQ 的 topic/exchange/routing-key</li>
 *   <li>messageKey 用于幂等和日志排查</li>
 *   <li>headers 存放中间件元数据（traceId、来源节点、重试次数等），不承载业务字段</li>
 * </ul>
 */
public class MessageEnvelope {

    private final String channel;
    private final String eventType;
    private final String messageKey;
    private final String businessKey;
    private final byte[] payload;
    private final String contentType;
    private final Instant createdAt;
    private final Instant deliverAt;
    private final Map<String, String> headers;

    private MessageEnvelope(Builder builder) {
        if (builder.channel == null || builder.channel.isBlank()) {
            throw new IllegalArgumentException("channel must not be blank");
        }
        if (builder.payload == null) {
            throw new IllegalArgumentException("payload must not be null");
        }
        this.channel = builder.channel;
        this.eventType = builder.eventType;
        this.messageKey = builder.messageKey;
        this.businessKey = builder.businessKey;
        this.payload = builder.payload;
        this.contentType = builder.contentType != null ? builder.contentType : "application/json";
        this.createdAt = builder.createdAt != null ? builder.createdAt : Instant.now();
        this.deliverAt = builder.deliverAt;
        this.headers = builder.headers != null && !builder.headers.isEmpty()
                ? Collections.unmodifiableMap(new LinkedHashMap<>(builder.headers))
                : Collections.emptyMap();
    }

    public String getChannel() { return channel; }
    public String getEventType() { return eventType; }
    public String getMessageKey() { return messageKey; }
    public String getBusinessKey() { return businessKey; }
    public byte[] getPayload() { return payload; }
    public String getContentType() { return contentType; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getDeliverAt() { return deliverAt; }
    public Map<String, String> getHeaders() { return headers; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private String channel;
        private String eventType;
        private String messageKey;
        private String businessKey;
        private byte[] payload;
        private String contentType;
        private Instant createdAt;
        private Instant deliverAt;
        private Map<String, String> headers;

        public Builder channel(String channel) { this.channel = channel; return this; }
        public Builder eventType(String eventType) { this.eventType = eventType; return this; }
        public Builder messageKey(String messageKey) { this.messageKey = messageKey; return this; }
        public Builder businessKey(String businessKey) { this.businessKey = businessKey; return this; }
        public Builder payload(byte[] payload) { this.payload = payload; return this; }
        public Builder contentType(String contentType) { this.contentType = contentType; return this; }
        public Builder createdAt(Instant createdAt) { this.createdAt = createdAt; return this; }
        public Builder deliverAt(Instant deliverAt) { this.deliverAt = deliverAt; return this; }
        public Builder header(String key, String value) {
            if (this.headers == null) this.headers = new LinkedHashMap<>();
            this.headers.put(key, value);
            return this;
        }
        public Builder headers(Map<String, String> headers) {
            if (headers != null) {
                if (this.headers == null) this.headers = new LinkedHashMap<>();
                this.headers.putAll(headers);
            }
            return this;
        }
        public MessageEnvelope build() { return new MessageEnvelope(this); }
    }
}
