package com.im.codec;

import com.im.api.content.*;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class ContentSerializerTest {

    // ── TEXT ──

    @Test
    void serializeTextRoundTrip() {
        TextContent original = new TextContent("Hello, IM!");
        byte[] bytes = ContentSerializer.toBytes(original);

        assertNotNull(bytes);
        assertTrue(bytes.length > 0);

        IMessageContent restored = ContentSerializer.fromBytes(ContentType.TEXT, bytes);
        assertInstanceOf(TextContent.class, restored);
        assertEquals(original, restored);
    }

    @Test
    void serializeTextToExpectedJson() {
        TextContent content = new TextContent("hello");
        byte[] bytes = ContentSerializer.toBytes(content);
        String json = new String(bytes, StandardCharsets.UTF_8);
        // Jackson 字段按字母序: text
        assertTrue(json.contains("\"text\""));
        assertTrue(json.contains("\"hello\""));
    }

    // ── FILE ──

    @Test
    void serializeFileRoundTrip() {
        FileContent original = new FileContent("doc.pdf", 1024L, "https://cdn.example.com/doc.pdf");
        byte[] bytes = ContentSerializer.toBytes(original);

        IMessageContent restored = ContentSerializer.fromBytes(ContentType.FILE, bytes);
        assertInstanceOf(FileContent.class, restored);
        assertEquals(original, restored);
    }

    @Test
    void serializeFileToExpectedJson() {
        FileContent content = new FileContent("doc.pdf", 1024, "http://u");
        byte[] bytes = ContentSerializer.toBytes(content);
        String json = new String(bytes, StandardCharsets.UTF_8);
        assertTrue(json.contains("\"fileName\""));
        assertTrue(json.contains("\"doc.pdf\""));
        assertTrue(json.contains("\"fileSize\""));
    }

    // ── IMAGE ──

    @Test
    void serializeImageRoundTrip() {
        ImageContent original = new ImageContent(800, 600, "png", 65536L, "https://cdn.example.com/img.png");
        byte[] bytes = ContentSerializer.toBytes(original);

        IMessageContent restored = ContentSerializer.fromBytes(ContentType.IMAGE, bytes);
        assertInstanceOf(ImageContent.class, restored);
        assertEquals(original, restored);
    }

    // ── SYSTEM ──

    @Test
    void systemContentSerializesToEmptyBytes() {
        SystemContent content = new SystemContent("user_online", "User is now online");
        byte[] bytes = ContentSerializer.toBytes(content);
        assertEquals(0, bytes.length);
    }

    @Test
    void nullContentSerializesToEmptyBytes() {
        byte[] bytes = ContentSerializer.toBytes(null);
        assertEquals(0, bytes.length);
    }

    // ── Deserialization edge cases ──

    @Test
    void deserializeSystemFromEmptyBody() {
        IMessageContent result = ContentSerializer.fromBytes(ContentType.SYSTEM, new byte[0]);
        assertInstanceOf(SystemContent.class, result);
    }

    @Test
    void deserializeSystemFromNullBody() {
        IMessageContent result = ContentSerializer.fromBytes(ContentType.SYSTEM, null);
        assertInstanceOf(SystemContent.class, result);
    }

    @Test
    void deserializeNonSystemFromEmptyBodyThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> ContentSerializer.fromBytes(ContentType.TEXT, new byte[0]));
    }
}
