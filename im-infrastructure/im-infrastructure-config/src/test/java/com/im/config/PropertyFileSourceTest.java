package com.im.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PropertyFileSourceTest {

    @Test
    void shouldLoadFromClasspath() {
        PropertyFileSource source = new PropertyFileSource("application.properties");
        var data = source.load();

        assertEquals("localhost", data.get("test.host"));
        assertEquals("8080", data.get("test.port"));
        assertEquals("true", data.get("test.enabled"));
        // _config.order 是元数据，不应出现在数据中
        assertFalse(data.containsKey("_config.order"));
    }

    @Test
    void shouldReturnEmptyForNonexistentFile() {
        PropertyFileSource source = new PropertyFileSource("nonexistent.properties");
        assertTrue(source.load().isEmpty());
    }

    @Test
    void shouldReadOrderFromFile() {
        PropertyFileSource source = new PropertyFileSource("application.properties");
        assertEquals(100, source.order());
    }

    @Test
    void shouldUseDefaultOrderWhenNotDefined() {
        // 创建一个没有 _config.order 的临时文件来测试默认值
        // 用不存在的文件验证默认值
        PropertyFileSource source = new PropertyFileSource("nonexistent.properties");
        assertEquals(200, source.order());
    }
}
