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
}
