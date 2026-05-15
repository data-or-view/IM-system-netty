package com.im.infrastructure.message;

/**
 * 消息体编解码抽象。
 *
 * <p>统一负责对象与 byte[] 之间的转换，避免业务侧散落 ObjectMapper / protobuf 细节。
 * 参考 cinema-message-middleware 的 MessageCodec 设计。
 */
public interface MessageCodec {

    byte[] encode(Object payload);

    <T> T decode(byte[] payloadBytes, Class<T> targetType);
}
