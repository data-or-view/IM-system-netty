package com.im.core.file;

public record UploadSession(String fileId,
                            String uploadId,
                            String bucket,
                            String objectKey,
                            String userId,
                            String fileName,
                            long fileSize,
                            String contentType,
                            String hash,
                            String fileGroup,
                            boolean multipart) {
}
