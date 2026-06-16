package com.im.api;

/**
 * Password hashing port used by authentication flows.
 */
public interface IPasswordHasher {

    String hash(String rawPassword);

    boolean matches(String rawPassword, String passwordHash);
}
