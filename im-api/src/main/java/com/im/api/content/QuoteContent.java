package com.im.api.content;

import java.util.Objects;

/**
 * 引用回复消息内容。
 * 对标 OpenIM QuoteElem。
 */
public class QuoteContent implements IMessageContent {

    private String text;
    private String quotedMessageId;
    private String quotedSenderId;
    private String quotedContent; // 被引用消息的摘要文本

    public QuoteContent() {}

    public QuoteContent(String text, String quotedMessageId, String quotedSenderId, String quotedContent) {
        this.text = text;
        this.quotedMessageId = quotedMessageId;
        this.quotedSenderId = quotedSenderId;
        this.quotedContent = quotedContent;
    }

    public String getText() { return text; }
    public void setText(String text) { this.text = text; }

    public String getQuotedMessageId() { return quotedMessageId; }
    public void setQuotedMessageId(String quotedMessageId) { this.quotedMessageId = quotedMessageId; }

    public String getQuotedSenderId() { return quotedSenderId; }
    public void setQuotedSenderId(String quotedSenderId) { this.quotedSenderId = quotedSenderId; }

    public String getQuotedContent() { return quotedContent; }
    public void setQuotedContent(String quotedContent) { this.quotedContent = quotedContent; }

    @Override
    public ContentType getContentType() { return ContentType.QUOTE; }

    @Override
    public void validate() {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("quote text must not be null or blank");
        }
        if (quotedMessageId == null || quotedMessageId.isBlank()) {
            throw new IllegalArgumentException("quotedMessageId must not be null or blank");
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof QuoteContent that)) return false;
        return Objects.equals(text, that.text)
                && Objects.equals(quotedMessageId, that.quotedMessageId)
                && Objects.equals(quotedSenderId, that.quotedSenderId)
                && Objects.equals(quotedContent, that.quotedContent);
    }

    @Override
    public int hashCode() { return Objects.hash(text, quotedMessageId); }

    @Override
    public String toString() {
        return "QuoteContent{text='" + text + "', quoted=" + quotedMessageId + "}";
    }
}
