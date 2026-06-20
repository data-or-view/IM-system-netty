package com.im.api;

/**
 * Persistent refresh token registry.
 *
 * <p>The store never receives or stores the plain refresh token. Callers pass a
 * stable token id plus a one-way hash so leaked database rows cannot be used as
 * bearer credentials.</p>
 */
public interface IRefreshTokenStore {

    void save(String tokenId, String userId, String tokenHash, int appManagerLevel,
              long issuedAt, long expiresAt);

    RefreshTokenRecord findActive(String tokenId);

    void revoke(String tokenId, long revokedAt);
}
