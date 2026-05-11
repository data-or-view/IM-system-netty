package com.im.api.content;

import java.util.Objects;

/**
 * 纯文本消息内容。
 * 支持最大 64KB 文本（IM 场景下长文本基本够用，长文本应走文件类型）。
 */
public class TextContent implements IMessageContent {

    /** 最大文本长度（64KB = 65536 字符） */
    public static final int MAX_TEXT_LENGTH = 65536;

    private String text;

    /** Jackson 反序列化用 */
    public TextContent() {}

    public TextContent(String text) {
        this.text = text;
    }

    public String getText() { return text; }
    public void setText(String text) { this.text = text; }

    @Override
    public ContentType getContentType() { return ContentType.TEXT; }

    @Override
    public void validate() {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("text content must not be null or blank");
        }
        if (text.length() > MAX_TEXT_LENGTH) {
            throw new IllegalArgumentException(
                    "text too long: " + text.length() + " (max " + MAX_TEXT_LENGTH + ")");
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TextContent that)) return false;
        return Objects.equals(text, that.text);
    }

    @Override
    public int hashCode() { return Objects.hash(text); }

    @Override
    public String toString() {
        return "TextContent{text='" + (text != null ? text.substring(0, Math.min(text.length(), 50)) : "") + "'}";
    }
}
