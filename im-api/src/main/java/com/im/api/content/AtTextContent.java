package com.im.api.content;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * @提及消息内容。
 * 对标 OpenIM AtElem。
 */
public class AtTextContent implements IMessageContent {

    private String text;
    private List<String> atUserList;

    public AtTextContent() {}

    public AtTextContent(String text, List<String> atUserList) {
        this.text = text;
        this.atUserList = atUserList != null ? List.copyOf(atUserList) : Collections.emptyList();
    }

    public String getText() { return text; }
    public void setText(String text) { this.text = text; }

    public List<String> getAtUserList() { return atUserList; }
    public void setAtUserList(List<String> atUserList) {
        this.atUserList = atUserList != null ? List.copyOf(atUserList) : Collections.emptyList();
    }

    @Override
    public ContentType getContentType() { return ContentType.AT_TEXT; }

    @Override
    public void validate() {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("at text must not be null or blank");
        }
        if (atUserList == null || atUserList.isEmpty()) {
            throw new IllegalArgumentException("atUserList must not be null or empty");
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AtTextContent that)) return false;
        return Objects.equals(text, that.text) && Objects.equals(atUserList, that.atUserList);
    }

    @Override
    public int hashCode() { return Objects.hash(text, atUserList); }

    @Override
    public String toString() {
        return "AtTextContent{text='" + text + "', at=" + atUserList + "}";
    }
}
