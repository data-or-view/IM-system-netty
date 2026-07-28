package com.im.api;

import java.util.Map;

/**
 * Object-storage POST form data constrained by a server-issued upload policy.
 */
public record PresignedPostPolicy(String uploadUrl,
                                  Map<String, String> formFields,
                                  String fileField) {
}
