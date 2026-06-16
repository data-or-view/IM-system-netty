package com.im.api;

/**
 * User credential store.
 *
 * <p>Separated from public user profile APIs to avoid leaking password hash
 * fields through normal user queries.</p>
 */
public interface IUserCredentialStore {

    String getPasswordHash(String userId);

    void setPasswordHash(String userId, String passwordHash);
}
