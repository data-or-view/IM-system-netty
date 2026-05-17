package com.im.api.content;

import java.util.Objects;

/**
 * 自定义消息内容（红包、名片、业务透传等）。
 * 对标 OpenIM CustomElem。
 */
public class CustomContent implements IMessageContent {

    private String data;        // 自定义数据（JSON字符串）
    private String description; // 描述
    private String extension;   // 扩展字段

    public CustomContent() {}

    public CustomContent(String data, String description, String extension) {
        this.data = data;
        this.description = description;
        this.extension = extension;
    }

    public String getData() { return data; }
    public void setData(String data) { this.data = data; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getExtension() { return extension; }
    public void setExtension(String extension) { this.extension = extension; }

    @Override
    public ContentType getContentType() { return ContentType.CUSTOM; }

    @Override
    public void validate() {
        if (data == null || data.isBlank()) {
            throw new IllegalArgumentException("custom data must not be null or blank");
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CustomContent that)) return false;
        return Objects.equals(data, that.data)
                && Objects.equals(description, that.description)
                && Objects.equals(extension, that.extension);
    }

    @Override
    public int hashCode() { return Objects.hash(data, description, extension); }

    @Override
    public String toString() {
        return "CustomContent{desc='" + description + "'}";
    }
}
