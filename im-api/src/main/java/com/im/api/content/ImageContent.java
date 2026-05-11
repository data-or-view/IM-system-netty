package com.im.api.content;

import java.util.Objects;
import java.util.Set;

/**
 * 图片消息内容。
 * 支持主流图片格式 + 最大 20MB。
 */
public class ImageContent implements IMessageContent {

    /** 最大图片 20MB */
    public static final long MAX_IMAGE_SIZE = 20L * 1024 * 1024;

    /** 最大尺寸 */
    public static final int MAX_DIMENSION = 16384;

    private static final Set<String> ALLOWED_FORMATS = Set.of(
            "png", "jpg", "jpeg", "gif", "webp", "bmp"
    );

    private int width;
    private int height;
    private String format;
    private long fileSize;
    private String url;

    /** Jackson 反序列化用 */
    public ImageContent() {}

    public ImageContent(int width, int height, String format, long fileSize, String url) {
        this.width = width;
        this.height = height;
        this.format = format;
        this.fileSize = fileSize;
        this.url = url;
    }

    public int getWidth() { return width; }
    public void setWidth(int width) { this.width = width; }

    public int getHeight() { return height; }
    public void setHeight(int height) { this.height = height; }

    public String getFormat() { return format; }
    public void setFormat(String format) { this.format = format; }

    public long getFileSize() { return fileSize; }
    public void setFileSize(long fileSize) { this.fileSize = fileSize; }

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    @Override
    public ContentType getContentType() { return ContentType.IMAGE; }

    @Override
    public void validate() {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException(
                    "image dimensions must be positive: " + width + "x" + height);
        }
        if (width > MAX_DIMENSION || height > MAX_DIMENSION) {
            throw new IllegalArgumentException(
                    "image dimensions too large: " + width + "x" + height + " (max " + MAX_DIMENSION + ")");
        }
        if (format == null || format.isBlank()) {
            throw new IllegalArgumentException("image format must not be null or blank");
        }
        if (!ALLOWED_FORMATS.contains(format.toLowerCase())) {
            throw new IllegalArgumentException("unsupported image format: " + format);
        }
        if (fileSize <= 0) {
            throw new IllegalArgumentException("image file size must be positive, got: " + fileSize);
        }
        if (fileSize > MAX_IMAGE_SIZE) {
            throw new IllegalArgumentException(
                    "image too large: " + fileSize + " bytes (max " + MAX_IMAGE_SIZE + ")");
        }
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("image URL must not be null or blank");
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ImageContent that)) return false;
        return width == that.width && height == that.height
                && fileSize == that.fileSize
                && Objects.equals(format, that.format)
                && Objects.equals(url, that.url);
    }

    @Override
    public int hashCode() { return Objects.hash(width, height, format, fileSize, url); }

    @Override
    public String toString() {
        return "ImageContent{" + width + "x" + height + ", " + format + ", size=" + fileSize + "}";
    }
}
