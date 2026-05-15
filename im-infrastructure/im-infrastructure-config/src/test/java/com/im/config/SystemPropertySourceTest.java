package com.im.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SystemPropertySourceTest {

    @AfterEach
    void cleanup() {
        System.clearProperty("test.im.host");
        System.clearProperty("test.im.port");
        System.clearProperty("other.key");
    }

    @Test
    void shouldLoadMatchingSystemProperties() {
        System.setProperty("test.im.host", "localhost");
        System.setProperty("test.im.port", "6379");

        SystemPropertySource source = new SystemPropertySource("test.im.");
        var data = source.load();

        assertEquals("localhost", data.get("test.im.host"));
        assertEquals("6379", data.get("test.im.port"));
    }

    @Test
    void shouldFilterByPrefix() {
        System.setProperty("test.im.host", "value");
        System.setProperty("other.key", "should-not-appear");

        SystemPropertySource source = new SystemPropertySource("test.im.");
        var data = source.load();

        assertTrue(data.containsKey("test.im.host"));
        assertFalse(data.containsKey("other.key"));
    }

    @Test
    void shouldBeEmptyWhenNoMatchingProperties() {
        SystemPropertySource source = new SystemPropertySource("zzz.nonexistent.prefix.");
        assertTrue(source.load().isEmpty());
    }

    @Test
    void shouldHaveCorrectOrder() {
        SystemPropertySource source = new SystemPropertySource();
        assertEquals(1, source.order());
    }
}
