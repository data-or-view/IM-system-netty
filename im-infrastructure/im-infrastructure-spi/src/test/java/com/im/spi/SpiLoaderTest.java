package com.im.spi;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SpiLoaderTest {

    @Test
    void shouldLoadByName() {
        Greeting greeting = SpiLoader.load(Greeting.class, "english");
        assertEquals("Hello", greeting.say());
    }

    @Test
    void shouldLoadChineseImpl() {
        Greeting greeting = SpiLoader.load(Greeting.class, "chinese");
        assertEquals("你好", greeting.say());
    }

    @Test
    void shouldLoadDefault() {
        Greeting greeting = SpiLoader.loadDefault(Greeting.class);
        assertEquals("Hello", greeting.say());
    }

    @Test
    void shouldLoadAllImpls() {
        Map<String, Greeting> all = SpiLoader.loadAll(Greeting.class);
        assertEquals(2, all.size());
    }

    @Test
    void shouldReturnSameInstanceOnRepeatedLoad() {
        Greeting a = SpiLoader.load(Greeting.class, "english");
        Greeting b = SpiLoader.load(Greeting.class, "english");
        assertSame(a, b);
    }

    @Test
    void shouldThrowWhenNameNotFound() {
        assertThrows(IllegalArgumentException.class,
                () -> SpiLoader.load(Greeting.class, "nonexistent"));
    }

    // ── SPI 接口 ──

    @Spi("english")
    public interface Greeting {
        String say();
    }

    // ── 实现 ──

    public static class EnglishGreeting implements Greeting {
        @Override
        public String say() { return "Hello"; }
    }

    public static class ChineseGreeting implements Greeting {
        @Override
        public String say() { return "你好"; }
    }
}
