package com.im.bootstrap;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RocketMqConfigTemplateTest {

    @Test
    void rootApplicationTemplateDeclaresProductionRocketMqQueueConfigKeys() throws Exception {
        Map<String, Object> im = imConfig(Path.of("../config/application.yml"));

        assertEquals("rocketmq", nested(im, "mq", "type"));
        assertRequiredRocketMqFields(im);
    }

    @Test
    void macbookDevTemplateDeclaresProductionRocketMqQueueConfigKeys() throws Exception {
        Map<String, Object> im = imConfig(Path.of("../config/application-macbook-dev.yml"));

        assertEquals("rocketmq", nested(im, "mq", "type"));
        assertRequiredRocketMqFields(im);
    }

    @Test
    void serverResourceTemplateDeclaresProductionRocketMqQueueConfigKeys() throws Exception {
        Map<String, Object> im = imConfig(Path.of("src/main/resources/application.yml"));

        assertEquals("rocketmq", nested(im, "mq", "type"));
        assertRequiredRocketMqFields(im);
    }

    private static void assertRequiredRocketMqFields(Map<String, Object> im) {
        assertTrue(nested(im, "rocketmq", "name-server") instanceof String);
        assertTrue(nested(im, "rocketmq", "producer", "group") instanceof String);
        assertTrue(nested(im, "rocketmq", "consumer", "group-prefix") instanceof String);
        assertTrue(nested(im, "rocketmq", "topic-prefix") instanceof String);
        assertTrue(nested(im, "rocketmq", "send", "timeout-ms") instanceof Number);
        assertTrue(nested(im, "rocketmq", "retry-times") instanceof Number);
        assertTrue(nested(im, "rocketmq", "consumer", "consume-from-where") instanceof String);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> imConfig(Path path) throws IOException {
        Yaml yaml = new Yaml();
        try (InputStream in = Files.newInputStream(path)) {
            Map<String, Object> root = yaml.load(in);
            return (Map<String, Object>) root.get("im");
        }
    }

    @SuppressWarnings("unchecked")
    private static Object nested(Map<String, Object> map, String first, String... rest) {
        Object value = map.get(first);
        for (String key : rest) {
            value = ((Map<String, Object>) value).get(key);
        }
        return value;
    }
}
