package com.im.spi;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记 SPI 接口的实现，并指定名称。
 *
 * <pre>{@code
 * @SpiImpl("redis")
 * public class RedisCache<K, V> implements Cache<K, V> { ... }
 * }</pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface SpiImpl {

    /**
     * 实现名称，用于 {@link SpiLoader} 按名加载。
     */
    String value();
}
