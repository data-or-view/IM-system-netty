package com.im.core.file;

public record FileUploadCompleteResult(String fileUrl,
                                       String fileId,
                                       String objectKey,
                                       String fileName,
                                       String mimeType,
                                       long fileSize) {
}
