package com.im.core.file;

public record MultipartSignResult(String fileId,
                                  String objectKey,
                                  String uploadId,
                                  int expiresIn) {
}
