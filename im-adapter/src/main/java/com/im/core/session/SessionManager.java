package com.im.core.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.im.api.IConnectionSession;
import com.im.api.ISessionManager;
import com.im.api.MultiLoginStrategy;
import com.im.core.serialization.jackson.ObjectMapperProvider;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 会话管理器实现，支持多端在线。
 *
 * 双索引映射：
 *   Channel → ConnectionSession（按对象引用区分）
 *   userId  → List<ConnectionSession>（多端登录）
 *
 * 多端登录策略：
 *   · ALLOW_MULTIPLE（默认）：新增 session 追加到列表
 *   · KICK_OLD：踢掉旧的
 *   · REJECT_NEW：返回 null，调用方自行决定是否断开连接
 *
 * 注意 key 用 Channel 对象引用而非 channel.id()：
 *   Netty 的 EmbeddedChannel 所有实例共享同一 ID（"0xembedded"），
 *   用 String key 会导致不同 Channel 在 map 中互相覆盖。
 */
public class SessionManager implements ISessionManager {

    private static final Logger log = LoggerFactory.getLogger(SessionManager.class);

    /** Channel（对象引用）→ ConnectionSession */
    private final ConcurrentMap<Channel, ConnectionSession> channelSessions = new ConcurrentHashMap<>();

    /** userId → session 列表（多端在线） */
    private final ConcurrentMap<String, CopyOnWriteArrayList<ConnectionSession>> userSessions = new ConcurrentHashMap<>();

    private static final ObjectMapper MAPPER = ObjectMapperProvider.get();

    private volatile MultiLoginStrategy loginStrategy = MultiLoginStrategy.ALLOW_MULTIPLE;

    @Override
    public IConnectionSession createSession(Channel channel) {
        ConnectionSession session = new ConnectionSession(channel);
        channelSessions.put(channel, session);
        return session;
    }

    @Override
    public IConnectionSession removeSession(Channel channel) {
        ConnectionSession session = channelSessions.remove(channel);
        if (session != null && session.getUserId() != null) {
            CopyOnWriteArrayList<ConnectionSession> sessions = userSessions.get(session.getUserId());
            if (sessions != null) {
                sessions.remove(session);
                if (sessions.isEmpty()) {
                    userSessions.remove(session.getUserId());
                }
            }
            log.info("Session removed: userId={}, sessionId={}", session.getUserId(), session.getSessionId());
        }
        return session;
    }

    @Override
    public IConnectionSession getByChannel(Channel channel) {
        return channelSessions.get(channel);
    }

    @Override
    public IConnectionSession getByUserId(String userId) {
        CopyOnWriteArrayList<ConnectionSession> sessions = userSessions.get(userId);
        return (sessions != null && !sessions.isEmpty()) ? sessions.get(0) : null;
    }

    @Override
    public List<IConnectionSession> getSessionsByUserId(String userId) {
        CopyOnWriteArrayList<ConnectionSession> sessions = userSessions.get(userId);
        return sessions != null ? Collections.unmodifiableList(new ArrayList<>(sessions))
                : Collections.emptyList();
    }

    @Override
    public IConnectionSession bindUser(Channel channel, String userId) {
        ConnectionSession session = (ConnectionSession) channelSessions.get(channel);
        if (session == null) {
            session = new ConnectionSession(channel);
            channelSessions.put(channel, session);
        }

        IConnectionSession kicked = null;

        switch (loginStrategy) {
            case KICK_OLD -> {
                // 踢掉所有旧端
                CopyOnWriteArrayList<ConnectionSession> oldSessions = userSessions.put(userId, new CopyOnWriteArrayList<>());
                if (oldSessions != null) {
                    for (ConnectionSession old : oldSessions) {
                        if (old != session) {
                            sendKickedNotification(old, "KICK_OLD");
                            old.getChannel().close();
                            channelSessions.remove(old.getChannel(), old);
                            log.info("Kicked old session: userId={}, oldSessionId={}", userId, old.getSessionId());
                            kicked = old;
                        }
                    }
                }
                userSessions.get(userId).add(session);
            }
            case SAME_TERM_KICK -> {
                // 仅踢同平台旧端
                int newPlatformId = session.getPlatformId();
                CopyOnWriteArrayList<ConnectionSession> sessions = userSessions.get(userId);
                if (sessions != null) {
                    for (ConnectionSession old : sessions) {
                        if (old != session && old.getPlatformId() == newPlatformId) {
                            sendKickedNotification(old, "SAME_TERM_KICK");
                            old.getChannel().close();
                            channelSessions.remove(old.getChannel(), old);
                            sessions.remove(old);
                            log.info("Kicked same-platform session: userId={}, platformId={}, oldSessionId={}",
                                    userId, newPlatformId, old.getSessionId());
                            kicked = old;
                        }
                    }
                }
                userSessions.computeIfAbsent(userId, k -> new CopyOnWriteArrayList<>()).add(session);
            }
            case REJECT_NEW -> {
                CopyOnWriteArrayList<ConnectionSession> existing = userSessions.get(userId);
                if (existing != null && !existing.isEmpty()) {
                    log.warn("Reject new login: userId={}, already online", userId);
                    return null; // 调用方应关闭连接
                }
                userSessions.computeIfAbsent(userId, k -> new CopyOnWriteArrayList<>()).add(session);
            }
            default -> // ALLOW_MULTIPLE
                    userSessions.computeIfAbsent(userId, k -> new CopyOnWriteArrayList<>()).add(session);
        }

        session.authenticate(userId, session.getPlatformId());
        log.info("User bound: userId={}, sessionId={}, strategy={}",
                userId, session.getSessionId(), loginStrategy);
        return kicked;
    }

    @Override
    public int scanIdleSessions(int idleSeconds) {
        long now = System.currentTimeMillis();
        long timeoutMs = idleSeconds * 1000L;
        List<ConnectionSession> toClose = new ArrayList<>();

        for (ConnectionSession session : channelSessions.values()) {
            if (!session.isAuthenticated()
                    && (now - session.getCreationTime()) >= timeoutMs) {
                toClose.add(session);
            }
        }

        for (ConnectionSession session : toClose) {
            session.getChannel().close();
            channelSessions.remove(session.getChannel(), session);
            log.info("Idle session closed: {}", session);
        }

        return toClose.size();
    }

    @Override
    public List<IConnectionSession> allSessions() {
        return Collections.unmodifiableList(new ArrayList<>(channelSessions.values()));
    }

    @Override
    public void clear() {
        channelSessions.values().forEach(s -> s.getChannel().close());
        channelSessions.clear();
        userSessions.clear();
        log.info("All sessions cleared");
    }

    /**
     * 向被踢 session 发送踢出通知。
     * 格式：{"op":"kicked","code":0,"data":{"reason":"SAME_TERM_KICK"}}
     */
    private void sendKickedNotification(ConnectionSession session, String reason) {
        try {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("reason", reason);
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("op", "kicked");
            msg.put("code", 0);
            msg.put("data", data);
            String json = MAPPER.writeValueAsString(msg);
            session.getChannel().writeAndFlush(new TextWebSocketFrame(json));
        } catch (Exception e) {
            log.warn("Failed to send kick notification to session {}", session.getSessionId(), e);
        }
    }

    /** 设置多端登录策略。 */
    public void setLoginStrategy(MultiLoginStrategy strategy) {
        this.loginStrategy = strategy;
        log.info("Multi-login strategy set to: {}", strategy);
    }

    /** 获取当前多端登录策略。 */
    public MultiLoginStrategy getLoginStrategy() {
        return loginStrategy;
    }
}
