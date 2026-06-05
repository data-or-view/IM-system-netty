package com.im.core.auth;

/**
 * 密码哈希接口。
 *
 * <p>认证流程只依赖此抽象，便于后续替换为 BCrypt/Argon2 或外部认证中心。</p>
 */
public interface IPasswordHasher {

    String hash(String rawPassword);

    boolean matches(String rawPassword, String passwordHash);
}
