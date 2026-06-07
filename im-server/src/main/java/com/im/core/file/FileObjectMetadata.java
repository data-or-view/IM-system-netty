package com.im.core.file;

public record FileObjectMetadata(String fileId,
                                 String userId,
                                 String bucket,
                                 String objectKey,
                                 String fileName,
                                 long fileSize,
                                 String contentType,
                                 String hash,
                                 String engine,
                                 String fileGroup,
                                 long createdAt) {
}
