package com.im.api.content;

import java.util.Objects;

/**
 * 图片消息内容（三级图片结构）。
 * 对标 OpenIM PictureElem → sourcePicture / bigPicture / snapshotPicture。
 */
public class ImageContent implements IMessageContent {

    /** 原图信息 */
    private PictureInfo sourcePicture;
    /** 大图（服务端/客户端压缩后的适配版本） */
    private PictureInfo bigPicture;
    /** 缩略图（聊天列表等场景使用） */
    private PictureInfo snapshotPicture;

    public ImageContent() {}

    public ImageContent(PictureInfo sourcePicture, PictureInfo bigPicture, PictureInfo snapshotPicture) {
        this.sourcePicture = sourcePicture;
        this.bigPicture = bigPicture;
        this.snapshotPicture = snapshotPicture;
    }

    public PictureInfo getSourcePicture() { return sourcePicture; }
    public void setSourcePicture(PictureInfo sourcePicture) { this.sourcePicture = sourcePicture; }

    public PictureInfo getBigPicture() { return bigPicture; }
    public void setBigPicture(PictureInfo bigPicture) { this.bigPicture = bigPicture; }

    public PictureInfo getSnapshotPicture() { return snapshotPicture; }
    public void setSnapshotPicture(PictureInfo snapshotPicture) { this.snapshotPicture = snapshotPicture; }

    @Override
    public ContentType getContentType() { return ContentType.IMAGE; }

    @Override
    public void validate() {
        if (sourcePicture == null) {
            throw new IllegalArgumentException("sourcePicture must not be null");
        }
        sourcePicture.validate();
        if (bigPicture != null) bigPicture.validate();
        if (snapshotPicture != null) snapshotPicture.validate();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ImageContent that)) return false;
        return Objects.equals(sourcePicture, that.sourcePicture)
                && Objects.equals(bigPicture, that.bigPicture)
                && Objects.equals(snapshotPicture, that.snapshotPicture);
    }

    @Override
    public int hashCode() { return Objects.hash(sourcePicture, bigPicture, snapshotPicture); }

    @Override
    public String toString() {
        return "ImageContent{source=" + sourcePicture + ", big=" + bigPicture + ", snapshot=" + snapshotPicture + "}";
    }

    /**
     * 单张图片信息。
     * 对标 OpenIM PictureBaseInfo。
     */
    public static class PictureInfo {
        private String uuid;
        private String type;      // MIME类型如 "image/png"
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
}
