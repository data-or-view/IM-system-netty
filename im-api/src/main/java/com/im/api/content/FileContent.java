package com.im.api.content;

import java.util.Objects;

/**
 * 文件传输内容。
 * 支持文件名字、大小（字节）、下载链接。
 */
public class FileContent implements IMessageContent {

    /** 最大单个文件 500MB */
    public static final long MAX_FILE_SIZE = 500L * 1024 * 1024;

    /** 允许的文件扩展名列表 */
    private static final java.util.Set<String> ALLOWED_EXTENSIONS = java.util.Set.of(
            "txt", "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
            "zip", "rar", "7z", "tar", "gz",
            "png", "jpg", "jpeg", "gif", "bmp", "webp",
            "mp4", "mp3", "wav", "avi", "mov",
            "json", "xml", "csv", "md"
    );

    private String uuid;
    private String fileName;
    private long fileSize;
    private String url;

    /** Jackson 反序列化用 */
    public FileContent() {}

    public FileContent(String uuid, String fileName, long fileSize, String url) {
        this.uuid = uuid;
        this.fileName = fileName;
        this.fileSize = fileSize;
        this.url = url;
    }

    public String getUuid() { return uuid; }
    public void setUuid(String uuid) { this.uuid = uuid; }

    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }

    public long getFileSize() { return fileSize; }
    public void setFileSize(long fileSize) { this.fileSize = fileSize; }

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    @Override
    public ContentType getContentType() { return ContentType.FILE; }

    @Override
    public void validate() {
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("file name must not be null or blank");
        }
        if (fileName.length() > 255) {
            throw new IllegalArgumentException("file name too long: " + fileName.length() + " (max 255)");
        }
        if (fileSize <= 0) {
            throw new IllegalArgumentException("file size must be positive, got: " + fileSize);
        }
        if (fileSize > MAX_FILE_SIZE) {
            throw new IllegalArgumentException(
                    "file too large: " + fileSize + " bytes (max " + MAX_FILE_SIZE + ")");
        }
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("file URL must not be null or blank");
        }
        // 检查扩展名
        int dot = fileName.lastIndexOf('.');
        if (dot < 0) {
            throw new IllegalArgumentException("file must have an extension: " + fileName);
        }
        String ext = fileName.substring(dot + 1).toLowerCase();
        if (!ALLOWED_EXTENSIONS.contains(ext)) {
            throw new IllegalArgumentException("file extension not allowed: ." + ext);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof FileContent that)) return false;
        return fileSize == that.fileSize
                && Objects.equals(uuid, that.uuid)
                && Objects.equals(fileName, that.fileName)
                && Objects.equals(url, that.url);
    }

    @Override
    public int hashCode() { return Objects.hash(uuid, fileName, fileSize, url); }

    @Override
    public String toString() {
        return "FileContent{name='" + fileName + "', size=" + fileSize + "}";
    }
}
