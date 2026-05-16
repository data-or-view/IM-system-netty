package com.im.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class YamlConfigSourceTest {

    @Test
    void shouldLoadYamlFromClasspath() {
        YamlConfigSource source = new YamlConfigSource("classpath:application.yml");
        var data = source.load();

        assertEquals("yaml-host", data.get("test.host"));
        assertEquals("9090", data.get("test.port"));
    }

    @Test
    void shouldFlattenNestedKeys() {
        YamlConfigSource source = new YamlConfigSource("classpath:application.yml");
        var data = source.load();

        assertEquals("deep-value", data.get("test.nested.key"));
    }

    @Test
    void shouldJoinListValues() {
        YamlConfigSource source = new YamlConfigSource("classpath:application.yml");
        var data = source.load();

        assertEquals("item1,item2,item3", data.get("test.list"));
    }

    @Test
    void shouldReturnEmptyForNonexistentFile() {
        YamlConfigSource source = new YamlConfigSource("classpath:nonexistent.yml");
        assertTrue(source.load().isEmpty());
    }

    @Test
    void shouldUseDefaultOrder() {
        YamlConfigSource source = new YamlConfigSource("classpath:application.yml");
        assertEquals(2, source.order());
    }

    @Test
    void shouldAcceptCustomOrder() {
        YamlConfigSource source = new YamlConfigSource("classpath:application.yml", 10);
        assertEquals(10, source.order());
    }

    @Test
    void configLoaderShouldDiscoverYaml() {
        // 验证 ConfigLoader 能发现 classpath:application.yml
        Config config = ConfigLoader.load();
        // application.yml 的 test.host=yaml-host（order=2）
        // application.properties 的 test.host=localhost（_config.order=100）
        // YAML 优先级更高，所以 test.host 应为 yaml-host
        String host = config.getString("test.host").orElse(null);
        assertEquals("yaml-host", host);
    }

    @Test
    void yamlValuesShouldBeAccessible() {
        Config config = ConfigLoader.load();
        // application.yml 独有的值
        assertEquals("deep-value", config.getString("test.nested.key").orElse(null));
        assertEquals("item1,item2,item3", config.getString("test.list").orElse(null));
    }

    @Test
    void flattenMethodShouldWork() {
        var nested = java.util.Map.<String, Object>of(
                "a", java.util.Map.of("b", "c"),
                "d", "e"
        );
        var flat = YamlConfigSource.flatten(nested);
        assertEquals("c", flat.get("a.b"));
        assertEquals("e", flat.get("d"));
    }
}
