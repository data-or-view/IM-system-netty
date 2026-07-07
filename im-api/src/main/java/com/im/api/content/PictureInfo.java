package com.im.api.content;

import java.util.Objects;

/**
 * 单张图片信息。
 * 对标 OpenIM PictureBaseInfo。
 */
public class PictureInfo {
    private String uuid;
    /** MIME 类型，如 {@code image/png}。 */
    private String type;
    private long fileSize;
    private int width;
    private int height;
    private String url;

    public PictureInfo() {}

    public PictureInfo(String uuid, String type, long fileSize, int width, int height, String url) {
        this.uuid = uuid;
        this.type = type;
        this.fileSize = fileSize;
        this.width = width;
        this.height = height;
        this.url = url;
    }

    public String getUuid() { return uuid; }
    public void setUuid(String uuid) { this.uuid = uuid; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public long getFileSize() { return fileSize; }
    public void setFileSize(long fileSize) { this.fileSize = fileSize; }

    public int getWidth() { return width; }
    public void setWidth(int width) { this.width = width; }

    public int getHeight() { return height; }
    public void setHeight(int height) { this.height = height; }

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public void validate() {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("image dimensions must be positive: " + width + "x" + height);
        }
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("image type must not be null or blank");
        }
        if (fileSize <= 0) {
            throw new IllegalArgumentException("image fileSize must be positive, got: " + fileSize);
        }
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("image url must not be null or blank");
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PictureInfo that)) return false;
        return fileSize == that.fileSize && width == that.width && height == that.height
                && Objects.equals(uuid, that.uuid) && Objects.equals(type, that.type)
                && Objects.equals(url, that.url);
    }

    @Override
    public int hashCode() { return Objects.hash(uuid, type, fileSize, width, height, url); }

    @Override
    public String toString() {
        return width + "x" + height + "," + type + ",url=" + url;
    }
}
