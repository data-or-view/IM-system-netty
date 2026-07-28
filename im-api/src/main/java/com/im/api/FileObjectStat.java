package com.im.api;

/**
 * Immutable object metadata used to verify a completed direct upload.
 */
public record FileObjectStat(long sizeBytes, String contentType) {
}
