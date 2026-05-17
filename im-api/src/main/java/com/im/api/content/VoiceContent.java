package com.im.api.content;

import java.util.Objects;

/**
 * 语音消息内容。
 * 对标 OpenIM SoundElem。
 */
public class VoiceContent implements IMessageContent {

    public static final long MAX_VOICE_SIZE = 10L * 1024 * 1024; // 10MB

    private String uuid;
    private String url;
    private long fileSize;
    private int duration; // 秒

    public VoiceContent() {}

    public VoiceContent(String uuid, String url, long fileSize, int duration) {
        this.uuid = uuid;
        this.url = url;
        this.fileSize = fileSize;
        this.duration = duration;
    }

    public String getUuid() { return uuid; }
    public void setUuid(String uuid) { this.uuid = uuid; }

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public long getFileSize() { return fileSize; }
    public void setFileSize(long fileSize) { this.fileSize = fileSize; }

    public int getDuration() { return duration; }
    public void setDuration(int duration) { this.duration = duration; }

    @Override
    public ContentType getContentType() { return ContentType.VOICE; }

    @Override
    public void validate() {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("voice url must not be null or blank");
        }
        if (fileSize <= 0) {
            throw new IllegalArgumentException("voice fileSize must be positive, got: " + fileSize);
        }
        if (fileSize > MAX_VOICE_SIZE) {
            throw new IllegalArgumentException("voice too large: " + fileSize + " (max " + MAX_VOICE_SIZE + ")");
        }
        if (duration <= 0) {
            throw new IllegalArgumentException("voice duration must be positive, got: " + duration);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof VoiceContent that)) return false;
        return fileSize == that.fileSize && duration == that.duration
                && Objects.equals(uuid, that.uuid) && Objects.equals(url, that.url);
    }

    @Override
    public int hashCode() { return Objects.hash(uuid, url, fileSize, duration); }

    @Override
    public String toString() {
        return "VoiceContent{duration=" + duration + "s, size=" + fileSize + "}";
    }
}
