package com.im.core.usecase;

import com.im.api.IAuthenticator;
import com.im.api.IMessageStore;
import com.im.api.Message;

import java.time.Duration;
import java.util.List;

/**
 * 登录业务：签发 token + 拉取离线消息。
 *
 * <p>路由注册由 {@code LoginHandler} 在策略检查（bindUser）之后调用，
 * 避免与被踢旧 session 的清理逻辑产生竞态。</p>
 */
public class LoginUseCase {

    private static final Duration ACCESS_TOKEN_TTL = Duration.ofHours(2);
    private static final Duration REFRESH_TOKEN_TTL = Duration.ofDays(30);

    private final IAuthenticator authenticator;
    private final IMessageStore messageStore;

    public LoginUseCase(IAuthenticator authenticator, IMessageStore messageStore) {
        this.authenticator = authenticator;
        this.messageStore = messageStore;
    }

    public record LoginResult(String token, String refreshToken, int platformId, List<Message> offlineMessages) {}

    public LoginResult execute(String userId, int platformId, int appManagerLevel) {
        String token = null;
        String refreshToken = null;
        if (authenticator != null) {
            token = authenticator.issueToken(userId, ACCESS_TOKEN_TTL, appManagerLevel);
            refreshToken = authenticator.issueRefreshToken(userId, REFRESH_TOKEN_TTL, appManagerLevel);
        }

        List<Message> offline = List.of();
        if (messageStore != null) {
            offline = messageStore.pullOffline(userId, 100);
        }

        return new LoginResult(token, refreshToken, platformId, offline);
    }
}
