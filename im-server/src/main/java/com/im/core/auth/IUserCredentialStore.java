package com.im.core.auth;

/**
 * 用户凭证仓库。
 *
 * <p>和公开用户资料接口分离，避免密码哈希字段从 API DTO 泄露到业务查询响应。</p>
 */
public interface IUserCredentialStore {

    String getPasswordHash(String userId);

    void setPasswordHash(String userId, String passwordHash);
}
