package com.im.api.content;

import org.junit.jupiter.api.Test;

import java.util.List;

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
        FileContent c = new FileContent("uuid-123", "report.pdf", 1024L, "https://cdn.example.com/report.pdf");
        assertDoesNotThrow(c::validate);
    }

    @Test
    void fileContentNullNameThrows() {
        FileContent c = new FileContent("uuid-123", null, 1024L, "http://u");
        assertThrows(IllegalArgumentException.class, c::validate);
    }

    @Test
    void fileContentNoExtensionThrows() {
        FileContent c = new FileContent("uuid-123", "nofileext", 1024L, "http://u");
        assertThrows(IllegalArgumentException.class, c::validate);
    }

    @Test
    void fileContentBadExtensionThrows() {
        FileContent c = new FileContent("uuid-123", "virus.exe", 1024L, "http://u");
        assertThrows(IllegalArgumentException.class, c::validate);
    }

    @Test
    void fileContentZeroSizeThrows() {
        FileContent c = new FileContent("uuid-123", "readme.txt", 0L, "http://u");
        assertThrows(IllegalArgumentException.class, c::validate);
    }

    @Test
    void fileContentTooLargeThrows() {
        FileContent c = new FileContent("uuid-123", "big.zip", FileContent.MAX_FILE_SIZE + 1, "http://u");
        assertThrows(IllegalArgumentException.class, c::validate);
    }

    // ── IMAGE ──

    @Test
    void imageContentValid() {
        ImageContent c = new ImageContent(
                new PictureInfo("u1", "image/png", 65536L, 800, 600, "https://cdn.example.com/source.png"),
                new PictureInfo("u2", "image/png", 16384L, 400, 300, "https://cdn.example.com/big.png"),
                new PictureInfo("u3", "image/png", 4096L, 200, 150, "https://cdn.example.com/snap.png"));
        assertDoesNotThrow(c::validate);
    }

    @Test
    void imageContentSourceNullThrows() {
        ImageContent c = new ImageContent(null, null, null);
        assertThrows(IllegalArgumentException.class, c::validate);
    }

    @Test
    void imageContentBadPictureInfoThrows() {
        ImageContent c = new ImageContent(
                new PictureInfo("u1", "", 0, 0, 0, ""),
                null, null);
        assertThrows(IllegalArgumentException.class, c::validate);
    }

    // ── VOICE ──

    @Test
    void voiceContentValid() {
        VoiceContent c = new VoiceContent("uuid-abc", "http://cdn.example.com/voice.mp3", 65536L, 30);
        assertDoesNotThrow(c::validate);
    }

    @Test
    void voiceContentNoUrlThrows() {
        VoiceContent c = new VoiceContent("uuid-abc", null, 65536L, 30);
        assertThrows(IllegalArgumentException.class, c::validate);
    }

    @Test
    void voiceContentZeroDurationThrows() {
        VoiceContent c = new VoiceContent("uuid-abc", "http://u", 65536L, 0);
        assertThrows(IllegalArgumentException.class, c::validate);
    }

    // ── VIDEO ──

    @Test
    void videoContentValid() {
        VideoContent c = new VideoContent(
                "http://cdn.example.com/video.mp4", "uuid-v", "video/mp4", 1048576L, 120,
                "http://cdn.example.com/snap.jpg", 640, 480, 32768L);
        assertDoesNotThrow(c::validate);
    }

    @Test
    void videoContentNoUrlThrows() {
        VideoContent c = new VideoContent(null, "uuid-v", "video/mp4", 1048576L, 120,
                "http://snap", 640, 480, 32768L);
        assertThrows(IllegalArgumentException.class, c::validate);
    }

    @Test
    void videoContentNoSnapshotUrlThrows() {
        VideoContent c = new VideoContent(
                "http://v", "uuid-v", "video/mp4", 1048576L, 120,
                null, 640, 480, 32768L);
        assertThrows(IllegalArgumentException.class, c::validate);
    }

    // ── LOCATION ──

    @Test
    void locationContentValid() {
        LocationContent c = new LocationContent("Beijing", 116.46, 39.92);
        assertDoesNotThrow(c::validate);
    }

    @Test
    void locationContentInvalidLngThrows() {
        LocationContent c = new LocationContent("X", 200.0, 0.0);
        assertThrows(IllegalArgumentException.class, c::validate);
    }

    @Test
    void locationContentInvalidLatThrows() {
        LocationContent c = new LocationContent("X", 0.0, 100.0);
        assertThrows(IllegalArgumentException.class, c::validate);
    }

    // ── AT_TEXT ──

    @Test
    void atTextContentValid() {
        AtTextContent c = new AtTextContent("@user1 hello", List.of("user1"));
        assertDoesNotThrow(c::validate);
    }

    @Test
    void atTextEmptyUserListThrows() {
        AtTextContent c = new AtTextContent("@all", List.of());
        assertThrows(IllegalArgumentException.class, c::validate);
    }

    // ── QUOTE ──

    @Test
    void quoteContentValid() {
        QuoteContent c = new QuoteContent("replied", "msg-001", "user1", "original msg");
        assertDoesNotThrow(c::validate);
    }

    @Test
    void quoteContentNoMsgIdThrows() {
        QuoteContent c = new QuoteContent("replied", null, "user1", "original");
        assertThrows(IllegalArgumentException.class, c::validate);
    }

    // ── CUSTOM ──

    @Test
    void customContentValid() {
        CustomContent c = new CustomContent("{\"key\":\"val\"}", "red packet", "");
        assertDoesNotThrow(c::validate);
    }

    @Test
    void customContentNoDataThrows() {
        CustomContent c = new CustomContent(null, "red packet", "");
        assertThrows(IllegalArgumentException.class, c::validate);
    }

    // ── IMessageContent interface ──

    @Test
    void contentTypesMatchEnum() {
        assertEquals(ContentType.TEXT, new TextContent("x").getContentType());
        assertEquals(ContentType.FILE, new FileContent("u", "a.txt", 1, "http://u").getContentType());
        assertEquals(ContentType.IMAGE, new ImageContent(
                new PictureInfo("u", "image/png", 1, 1, 1, "http://u"),
                null, null).getContentType());
        assertEquals(ContentType.SYSTEM, new SystemContent("t", null).getContentType());
        assertEquals(ContentType.VOICE, new VoiceContent("u", "http://u", 1, 1).getContentType());
        assertEquals(ContentType.VIDEO, new VideoContent("http://v", "u", "video/mp4", 1, 1,
                "http://s", 1, 1, 1).getContentType());
        assertEquals(ContentType.LOCATION, new LocationContent("X", 0, 0).getContentType());
        assertEquals(ContentType.AT_TEXT, new AtTextContent("x", List.of("u")).getContentType());
        assertEquals(ContentType.QUOTE, new QuoteContent("x", "mid", "uid", "c").getContentType());
        assertEquals(ContentType.CUSTOM, new CustomContent("{}", "d", "").getContentType());
    }
}
