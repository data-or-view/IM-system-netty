package com.im.core.handler;

import com.im.api.content.ContentType;
import com.im.api.content.SystemContent;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContentSerializerTest {

    @Test
    void systemContentKeepsDisplayTextInPayload() {
        SystemContent content = new SystemContent("group_member_joined", "Alice joined the group");

        byte[] bytes = ContentSerializer.toBytes(content);

        String json = new String(bytes, StandardCharsets.UTF_8);
        assertTrue(json.contains("group_member_joined"));
        assertTrue(json.contains("Alice joined the group"));
        SystemContent restored = (SystemContent) ContentSerializer.fromBytes(ContentType.SYSTEM, bytes);
        assertEquals("group_member_joined", restored.getSystemType());
        assertEquals("Alice joined the group", restored.getMessage());
    }
}
