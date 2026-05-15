package com.im.core.serialization;

/**
 * 序列化接口。
 *
 * <p>统一管理序列化与反序列化，屏蔽具体实现（Jackson / Protobuf 等）。
 *
 * @param <T> Java 类型
 * @param <R> 序列化结果类型（如 {@link String}、{@code byte[]}）
 */
public interface Serializer<T, R> {

    /**
     * 序列化。
     */
    R serialize(T source);

    /**
     * 反序列化。
     *
     * @param raw  待反序列化的数据
     * @param type 目标类型
     * @throws IllegalArgumentException raw 格式不合法时抛出
     */
    T deserialize(R raw, Class<T> type);
}
