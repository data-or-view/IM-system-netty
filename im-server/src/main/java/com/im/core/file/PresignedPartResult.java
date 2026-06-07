package com.im.core.file;

import java.util.Map;

public record PresignedPartResult(String uploadId,
                                  int partNumber,
                                  String uploadUrl,
                                  String method,
                                  Map<String, String> headers,
                                  int expiresIn) {
}
