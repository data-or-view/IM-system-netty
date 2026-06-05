package com.im.infrastructure.storage.usecase;

public record FileUploadResult(String fileUrl, String fileId, String fileName, String mimeType, long fileSize) {}
