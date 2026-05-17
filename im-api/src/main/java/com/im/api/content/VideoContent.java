package com.im.api.content;

import java.util.Objects;

/**
 * 视频消息内容。
 * 对标 OpenIM VideoElem。
 */
public class VideoContent implements IMessageContent {

    public static final long MAX_VIDEO_SIZE = 500L * 1024 * 1024; // 500MB

    private String videoUrl;
    private String videoUuid;
    private String videoType;   // MIME type
    private long videoSize;
    private int duration;       // 秒
    private String snapshotUrl; // 视频快照URL
    private int snapshotWidth;
    private int snapshotHeight;
    private long snapshotSize;

    public VideoContent() {}

    public VideoContent(String videoUrl, String videoUuid, String videoType, long videoSize,
                        int duration, String snapshotUrl, int snapshotWidth, int snapshotHeight, long snapshotSize) {
        this.videoUrl = videoUrl;
        this.videoUuid = videoUuid;
        this.videoType = videoType;
        this.videoSize = videoSize;
        this.duration = duration;
        this.snapshotUrl = snapshotUrl;
        this.snapshotWidth = snapshotWidth;
        this.snapshotHeight = snapshotHeight;
        this.snapshotSize = snapshotSize;
    }

    public String getVideoUrl() { return videoUrl; }
    public void setVideoUrl(String videoUrl) { this.videoUrl = videoUrl; }

    public String getVideoUuid() { return videoUuid; }
    public void setVideoUuid(String videoUuid) { this.videoUuid = videoUuid; }

    public String getVideoType() { return videoType; }
    public void setVideoType(String videoType) { this.videoType = videoType; }

    public long getVideoSize() { return videoSize; }
    public void setVideoSize(long videoSize) { this.videoSize = videoSize; }

    public int getDuration() { return duration; }
    public void setDuration(int duration) { this.duration = duration; }

    public String getSnapshotUrl() { return snapshotUrl; }
    public void setSnapshotUrl(String snapshotUrl) { this.snapshotUrl = snapshotUrl; }

    public int getSnapshotWidth() { return snapshotWidth; }
    public void setSnapshotWidth(int snapshotWidth) { this.snapshotWidth = snapshotWidth; }

    public int getSnapshotHeight() { return snapshotHeight; }
    public void setSnapshotHeight(int snapshotHeight) { this.snapshotHeight = snapshotHeight; }

    public long getSnapshotSize() { return snapshotSize; }
    public void setSnapshotSize(long snapshotSize) { this.snapshotSize = snapshotSize; }

    @Override
    public ContentType getContentType() { return ContentType.VIDEO; }

    @Override
    public void validate() {
        if (videoUrl == null || videoUrl.isBlank()) {
            throw new IllegalArgumentException("videoUrl must not be null or blank");
        }
        if (videoType == null || videoType.isBlank()) {
            throw new IllegalArgumentException("videoType must not be null or blank");
        }
        if (videoSize <= 0) {
            throw new IllegalArgumentException("videoSize must be positive, got: " + videoSize);
        }
        if (videoSize > MAX_VIDEO_SIZE) {
            throw new IllegalArgumentException("video too large: " + videoSize + " (max " + MAX_VIDEO_SIZE + ")");
        }
        if (duration <= 0) {
            throw new IllegalArgumentException("duration must be positive, got: " + duration);
        }
        if (snapshotUrl == null || snapshotUrl.isBlank()) {
            throw new IllegalArgumentException("snapshotUrl must not be null or blank");
        }
        if (snapshotWidth <= 0 || snapshotHeight <= 0) {
            throw new IllegalArgumentException("snapshot dimensions must be positive: " + snapshotWidth + "x" + snapshotHeight);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof VideoContent that)) return false;
        return videoSize == that.videoSize && duration == that.duration
                && snapshotWidth == that.snapshotWidth && snapshotHeight == that.snapshotHeight
                && snapshotSize == that.snapshotSize
                && Objects.equals(videoUrl, that.videoUrl) && Objects.equals(videoUuid, that.videoUuid)
                && Objects.equals(videoType, that.videoType) && Objects.equals(snapshotUrl, that.snapshotUrl);
    }

    @Override
    public int hashCode() { return Objects.hash(videoUrl, videoUuid, videoType, videoSize, duration); }

    @Override
    public String toString() {
        return "VideoContent{type=" + videoType + ", duration=" + duration + "s, size=" + videoSize + "}";
    }
}
