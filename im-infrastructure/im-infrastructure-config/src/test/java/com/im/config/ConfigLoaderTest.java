package com.im.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ConfigLoaderTest {

    @Test
    void propertyFileSourceShouldLoadFromClasspath() {
        PropertyFileSource source = new PropertyFileSource("application.properties");
        var data = source.load();

        assertEquals("localhost", data.get("test.host"));
        assertEquals("8080", data.get("test.port"));
        assertTrue(data.containsKey("test.enabled"));
    }

    @Test
    void shouldCorrectlyMergeByPriority() {
        // 模拟完整加载合并流程：
        // 环境变量（最高） > 系统属性 > 配置文件（最低）
        Config env = new MapConfig(java.util.Map.of(
                "app.name", "from-env",
                "app.port", "9000"
        ));
        Config sys = new MapConfig(java.util.Map.of(
                "app.name", "from-sys"
        ));
        Config props = new MapConfig(java.util.Map.of(
                "app.name", "from-props",
                "app.debug", "true"
        ));

        // 按优先级添加：env > sys > props
        Config config = CompositeConfig.builder()
                .add(env)    // order=0, 最高
                .add(sys)    // order=1
                .add(props)  // order=2, 最低
                .build();

        // env 优先
        assertEquals("from-env", config.getRequiredString("app.name"));
        // env 特有的值
        assertEquals(9000, config.getRequiredInt("app.port"));
        // 仅 props 有的值（fallback）
        assertTrue(config.getRequiredBoolean("app.debug"));
    }

    @Test
    void sourcesShouldHaveCorrectOrder() {
        assertTrue(new EnvSource().order() < new SystemPropertySource().order());
        assertTrue(new SystemPropertySource().order() < new PropertyFileSource().order());
    }
}
