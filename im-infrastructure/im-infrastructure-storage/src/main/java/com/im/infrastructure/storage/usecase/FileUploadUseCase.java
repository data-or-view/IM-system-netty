package com.im.infrastructure.storage.usecase;

import com.im.api.IFileStorageService;
import com.im.common.enums.ImErrorCode;
import com.im.common.exception.ImException;

import java.util.UUID;

public class FileUploadUseCase {

    /** 最大文件大小：10 MB */
    public static final int MAX_FILE_SIZE = 10 * 1024 * 1024;

    private static final String DEFAULT_BUCKET = "im-system";

    private final IFileStorageService fileStorage;

    public FileUploadUseCase(IFileStorageService fileStorage) {
        this.fileStorage = fileStorage;
    }

    public record FileUploadResult(String fileUrl, String fileId, String fileName, String mimeType, int fileSize) {}

    public FileUploadResult execute(String fileName, String mimeType, byte[] body) {
        if (body == null || body.length == 0) {
            throw new ImException(ImErrorCode.BAD_REQUEST, "file body is empty");
        }
        if (body.length > MAX_FILE_SIZE) {
            throw new ImException(ImErrorCode.BAD_REQUEST,
                    "file too large: " + body.length + " (max " + MAX_FILE_SIZE + ")");
        }

        String ext = extractExtension(fileName);
        String fileId = UUID.randomUUID().toString().replace("-", "");
        String objectId = "uploads/" + fileId + (ext != null ? ext : "");

        String fileUrl = fileStorage.upload(DEFAULT_BUCKET, objectId, body, mimeType);

        return new FileUploadResult(fileUrl, fileId, fileName != null ? fileName : "",
                mimeType != null ? mimeType : "", body.length);
    }

    private static String extractExtension(String fileName) {
        if (fileName == null) return null;
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) return null;
        String ext = fileName.substring(dot).toLowerCase();
        if (ext.matches("\\.(jpg|jpeg|png|gif|webp|bmp|mp4|mp3|wav|ogg|pdf|doc|docx|xls|xlsx|zip|txt|json|csv)")) {
            return ext;
        }
        return null;
    }
}
