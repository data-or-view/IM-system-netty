package com.im.config;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CompositeConfigTest {

    // 低优先级配置（基础值）
    private final Config base = new MapConfig(Map.of(
            "redis.host", "default-host",
            "redis.port", "6379",
            "app.name", "myapp"
    ));

    // 高优先级配置（覆盖值）
    private final Config override = new MapConfig(Map.of(
            "redis.host", "prod-host",
            "redis.port", "6380"
    ));

    @Test
    void highPriorityShouldOverrideLowPriority() {
        Config config = CompositeConfig.builder()
                .add(override)  // 高优先级
                .add(base)      // 低优先级
                .build();

        assertEquals("prod-host", config.getRequiredString("redis.host"));
        assertEquals(6380, config.getRequiredInt("redis.port"));
    }

    @Test
    void lowPriorityShouldServeAsFallback() {
        Config config = CompositeConfig.builder()
                .add(override)
                .add(base)
                .build();

        // app.name 只在 base 中存在，应该能从低优先级获取
        assertEquals("myapp", config.getRequiredString("app.name"));
    }

    @Test
    void shouldHandleSingleSource() {
        Config config = CompositeConfig.builder()
                .add(base)
                .build();

        assertEquals("myapp", config.getRequiredString("app.name"));
    }

    @Test
    void shouldReturnEmptyForMissingKey() {
        Config config = CompositeConfig.builder()
                .add(override)
                .add(base)
                .build();

        assertTrue(config.getString("nonexistent").isEmpty());
        assertFalse(config.hasKey("nonexistent"));
    }

    @Test
    void shouldUseFirstSourceForBoolean() {
        var src1 = new MapConfig(Map.of("feature.x", "true"));
        var src2 = new MapConfig(Map.of("feature.x", "false"));

        Config config = CompositeConfig.builder()
                .add(src1)
                .add(src2)
                .build();

        assertTrue(config.getRequiredBoolean("feature.x"));
    }

    @Test
    void shouldSupportThreeLevelPriority() {
        // 三级：最高 → 中间 → 最低
        Config high = new MapConfig(Map.of("key", "high"));
        Config mid = new MapConfig(Map.of("key", "mid", "mid.only", "value"));
        Config low = new MapConfig(Map.of("key", "low", "low.only", "value"));

        Config config = CompositeConfig.builder()
                .add(high)
                .add(mid)
                .add(low)
                .build();

        assertEquals("high", config.getRequiredString("key"));
        assertEquals("value", config.getRequiredString("mid.only"));
        assertEquals("value", config.getRequiredString("low.only"));
    }

    @Test
    void shouldReturnDefaultWhenKeyMissing() {
        Config config = CompositeConfig.builder()
                .add(override)
                .build();

        assertEquals("fallback", config.getString("nonexistent", "fallback"));
        assertEquals(42, config.getInt("nonexistent", 42));
    }

    @Test
    void hasKeyShouldCheckAllSources() {
        Config config = CompositeConfig.builder()
                .add(override)
                .add(base)
                .build();

        assertTrue(config.hasKey("redis.host"));
        assertTrue(config.hasKey("app.name"));
        assertFalse(config.hasKey("nonexistent"));
    }

    @Test
    void shouldBuildFromPropertyFileSource() {
        // 真实场景测试：从 properties 文件加载并合并
        PropertyFileSource source = new PropertyFileSource("application.properties");
        Config props = new MapConfig(source.load());

        Config config = CompositeConfig.builder()
                .add(props)
                .build();

        assertEquals("localhost", config.getRequiredString("test.host"));
    }
}
