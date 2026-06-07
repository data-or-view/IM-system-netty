package com.im.core.file;

public record FileDownloadSignResult(String fileId,
                                     String fileName,
                                     String fileUrl,
                                     long fileSize,
                                     String mimeType) {
}
