package com.im.infrastructure.storage.usecase;

public record MultipartUploadCompleteResult(String fileUrl, String fileId, String fileName, String mimeType) {}
