package com.im.core.file;

import java.util.Map;

public record PresignedUploadResult(String fileId,
                                    String objectKey,
                                    String uploadUrl,
                                    String method,
                                    Map<String, String> formFields,
                                    String fileField,
                                    int expiresIn) {
}
