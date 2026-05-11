package com.im.api.content;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ContentValidationTest {

    // ── TEXT ──

    @Test
    void textContentValid() {
        TextContent c = new TextContent("Hello");
        assertDoesNotThrow(c::validate);
    }

    @Test
    void textContentNullThrows() {
        TextContent c = new TextContent(null);
        assertThrows(IllegalArgumentException.class, c::validate);
    }

    @Test
    void textContentBlankThrows() {
        TextContent c = new TextContent("   ");
        assertThrows(IllegalArgumentException.class, c::validate);
    }

    @Test
    void textContentTooLongThrows() {
        StringBuilder sb = new StringBuilder(TextContent.MAX_TEXT_LENGTH + 1);
        sb.append("x".repeat(TextContent.MAX_TEXT_LENGTH + 1));
        TextContent c = new TextContent(sb.toString());
        assertThrows(IllegalArgumentException.class, c::validate);
    }

    // ── FILE ──

    @Test
    void fileContentValid() {
        FileContent c = new FileContent("report.pdf", 1024L, "https://cdn.example.com/report.pdf");
        assertDoesNotThrow(c::validate);
    }

    @Test
    void fileContentNullNameThrows() {
        FileContent c = new FileContent(null, 1024L, "http://u");
        assertThrows(IllegalArgumentException.class, c::validate);
    }

    @Test
    void fileContentNoExtensionThrows() {
        FileContent c = new FileContent("nofileext", 1024L, "http://u");
        assertThrows(IllegalArgumentException.class, c::validate);
    }

    @Test
    void fileContentBadExtensionThrows() {
        FileContent c = new FileContent("virus.exe", 1024L, "http://u");
        assertThrows(IllegalArgumentException.class, c::validate);
    }

    @Test
    void fileContentZeroSizeThrows() {
        FileContent c = new FileContent("readme.txt", 0L, "http://u");
        assertThrows(IllegalArgumentException.class, c::validate);
    }

    @Test
    void fileContentTooLargeThrows() {
        FileContent c = new FileContent("big.zip", FileContent.MAX_FILE_SIZE + 1, "http://u");
        assertThrows(IllegalArgumentException.class, c::validate);
    }

    // ── IMAGE ──

    @Test
    void imageContentValid() {
        ImageContent c = new ImageContent(800, 600, "png", 65536L, "https://cdn.example.com/img.png");
        assertDoesNotThrow(c::validate);
    }

    @Test
    void imageContentZeroWidthThrows() {
        ImageContent c = new ImageContent(0, 600, "png", 65536L, "http://u");
        assertThrows(IllegalArgumentException.class, c::validate);
    }

    @Test
    void imageContentBadFormatThrows() {
        ImageContent c = new ImageContent(800, 600, "tiff", 65536L, "http://u");
        assertThrows(IllegalArgumentException.class, c::validate);
    }

    @Test
    void imageContentFormatCaseInsensitive() {
        ImageContent c = new ImageContent(800, 600, "PNG", 65536L, "http://u");
        assertDoesNotThrow(c::validate);
    }

    @Test
    void imageContentTooLargeThrows() {
        ImageContent c = new ImageContent(800, 600, "png", ImageContent.MAX_IMAGE_SIZE + 1, "http://u");
        assertThrows(IllegalArgumentException.class, c::validate);
    }

    // ── SYSTEM ──

    @Test
    void systemContentValid() {
        SystemContent c = new SystemContent("user_online", "User online");
        assertDoesNotThrow(c::validate);
    }

    @Test
    void systemContentNullTypeThrows() {
        SystemContent c = new SystemContent(null, "MSG");
        assertThrows(IllegalArgumentException.class, c::validate);
    }

    @Test
    void systemContentBlankTypeThrows() {
        SystemContent c = new SystemContent("  ", "MSG");
        assertThrows(IllegalArgumentException.class, c::validate);
    }

    // ── IMessageContent interface ──

    @Test
    void contentTypesMatchEnum() {
        assertEquals(ContentType.TEXT, new TextContent("x").getContentType());
        assertEquals(ContentType.FILE, new FileContent("a.txt", 1, "http://u").getContentType());
        assertEquals(ContentType.IMAGE, new ImageContent(1, 1, "png", 1, "http://u").getContentType());
        assertEquals(ContentType.SYSTEM, new SystemContent("t", null).getContentType());
    }
}
