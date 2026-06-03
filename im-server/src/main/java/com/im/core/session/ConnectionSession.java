package com.im.core.session;

import com.im.api.IConnectionSession;
import com.im.api.ConnectionRef;
import com.im.api.PlatformID;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 连接会话实现。
 *
 * —— 连接级别的会话，关联 ConnectionRef ⇔ userId
 * —— 参考 RocketMQ 的 ClientChannelInfo：
 *    ConnectionRef connection ← 构造入参
 *    String clientId ← userId（authenticate 时设置）
 *    long lastUpdateTimestamp ← lastActiveTime（touch 时更新）
 *
 * 线程安全：除了 authenticate 外的字段都在构造时初始化，不可变。
 * authenticate 在 LoginHandler 的单线程上下文中调用，无需同步。
 */
public class ConnectionSession implements IConnectionSession {

    private final String sessionId;
    private final ConnectionRef connection;
    private final String remoteAddress;
    private final long creationTime;

    private volatile String userId;
    private volatile int platformId = PlatformID.DEFAULT;
    private volatile boolean authenticated;
    private final AtomicLong lastActiveTime;

    public ConnectionSession(ConnectionRef connection) {
        this.sessionId = UUID.randomUUID().toString();
        this.connection = Objects.requireNonNull(connection, "connection must not be null");
        this.remoteAddress = connection.remoteAddress();
        this.creationTime = System.currentTimeMillis();
        this.lastActiveTime = new AtomicLong(this.creationTime);
    }

    @Override
    public String getSessionId() { return sessionId; }

    @Override
    public String getUserId() { return userId; }

    @Override
    public int getPlatformId() { return platformId; }

    @Override
    public ConnectionRef getConnection() { return connection; }

    @Override
    public String getRemoteAddress() { return remoteAddress; }

    @Override
    public boolean isAuthenticated() { return authenticated; }

    @Override
    public void authenticate(String userId) {
        authenticate(userId, PlatformID.DEFAULT);
    }

    @Override
    public void authenticate(String userId, int platformId) {
        this.userId = userId;
        this.platformId = platformId;
        this.authenticated = true;
        touch();
    }

    @Override
    public long getLastActiveTime() { return lastActiveTime.get(); }

    @Override
    public void touch() { lastActiveTime.set(System.currentTimeMillis()); }

    @Override
    public long getCreationTime() { return creationTime; }

    @Override
    public String toString() {
        return "ConnectionSession{" +
                "sessionId='" + sessionId + '\'' +
                ", userId='" + userId + '\'' +
                ", platformId=" + platformId +
                ", remote=" + remoteAddress +
                ", authenticated=" + authenticated +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ConnectionSession that)) return false;
        return Objects.equals(sessionId, that.sessionId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sessionId);
    }
}
