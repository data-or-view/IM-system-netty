package com.im.api;

public record RefreshTokenRecord(
        String tokenId,
        String userId,
        String tokenHash,
        int appManagerLevel,
        long issuedAt,
        long expiresAt,
        long revokedAt
) {
    public boolean activeAt(long now) {
        return revokedAt <= 0 && expiresAt > now;
    }
}
