package com.im.spi;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记接口为 SPI 扩展点。
 *
 * <p>标注在接口上，表示这是一个可插拔的扩展点。配合 {@link SpiImpl} 和 {@link SpiLoader} 使用。
 *
 * <pre>{@code
 * @Spi("local")
 * public interface Cache<K, V> { ... }
 * }</pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface Spi {

    /**
     * 默认实现名称，对应 {@link SpiImpl#value()}。
     * 调用方不指定名称时使用此值。
     */
    String value() default "";
}
