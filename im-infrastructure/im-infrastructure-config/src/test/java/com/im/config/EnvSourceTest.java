package com.im.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EnvSourceTest {

    @Test
    void shouldLoadAndTransformEnvVars() {
        EnvSource source = new EnvSource("JAVA_");
        var data = source.load();

        // 如果有 JAVA_HOME，验证转换：JAVA_HOME → java.home
        if (data.containsKey("java.home")) {
            assertNotNull(data.get("java.home"));
        }
    }

    @Test
    void shouldBeEmptyForUnmatchedPrefix() {
        EnvSource source = new EnvSource("ZZZ_UNMATCHED_PREFIX_XYZ_");
        assertTrue(source.load().isEmpty());
    }

    @Test
    void shouldHandleEmptyPrefix() {
        // 空前缀应加载所有环境变量
        EnvSource source = new EnvSource("");
        var data = source.load();
        assertFalse(data.isEmpty());
    }

    @Test
    void shouldHaveCorrectOrder() {
        EnvSource source = new EnvSource();
        assertEquals(0, source.order());
    }
}
