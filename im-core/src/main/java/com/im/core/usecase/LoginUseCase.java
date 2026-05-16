package com.im.core.usecase;

import com.im.api.IAuthenticator;
import com.im.api.IMessageStore;
import com.im.api.IRouteTable;
import com.im.api.Message;

import java.time.Duration;
import java.util.List;

public class LoginUseCase {

    private static final Duration TOKEN_TTL = Duration.ofDays(30);

    private final IAuthenticator authenticator;
    private final IRouteTable routeTable;
    private final IMessageStore messageStore;
    private final String localNodeId;

    public LoginUseCase(IAuthenticator authenticator, IRouteTable routeTable,
                        IMessageStore messageStore, String localNodeId) {
        this.authenticator = authenticator;
        this.routeTable = routeTable;
        this.messageStore = messageStore;
        this.localNodeId = localNodeId;
    }

    public record LoginResult(String token, int platformId, List<Message> offlineMessages) {}

    public LoginResult execute(String userId, int platformId, int appManagerLevel) {
        String token = null;
        if (authenticator != null) {
            token = authenticator.issueToken(userId, TOKEN_TTL, appManagerLevel);
        }

        if (routeTable != null) {
            routeTable.online(userId, localNodeId);
            routeTable.setOnline(userId, platformId);
        }

        List<Message> offline = List.of();
        if (messageStore != null) {
            offline = messageStore.pullOffline(userId, 100);
        }

        return new LoginResult(token, platformId, offline);
    }
}
