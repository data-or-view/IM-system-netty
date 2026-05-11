package com.im.core.session;

import com.im.api.IConnectionSession;
import com.im.api.MultiLoginStrategy;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SessionManager 测试：会话创建、绑定、查询、清理。
 */
class SessionManagerTest {

    private SessionManager manager;

    @BeforeEach
    void setUp() {
        manager = new SessionManager();
    }

    @AfterEach
    void tearDown() {
        manager.clear();
    }

    @Test
    void createSession() {
        EmbeddedChannel ch = new EmbeddedChannel();
        IConnectionSession session = manager.createSession(ch);

        assertNotNull(session);
        assertNotNull(session.getSessionId());
        assertFalse(session.isAuthenticated());
        assertEquals(ch, session.getChannel());
        ch.close();
    }

    @Test
    void getByChannel() {
        EmbeddedChannel ch = new EmbeddedChannel();
        IConnectionSession created = manager.createSession(ch);

        IConnectionSession found = manager.getByChannel(ch);
        assertNotNull(found);
        assertEquals(created.getSessionId(), found.getSessionId());
        ch.close();
    }

    @Test
    void removeSession() {
        EmbeddedChannel ch = new EmbeddedChannel();
        manager.createSession(ch);
        assertNotNull(manager.getByChannel(ch));

        manager.removeSession(ch);
        assertNull(manager.getByChannel(ch));
        ch.close();
    }

    @Test
    void bindUserThenGetByUserId() {
        EmbeddedChannel ch = new EmbeddedChannel();
        manager.createSession(ch);
        manager.bindUser(ch, "user001");

        IConnectionSession found = manager.getByUserId("user001");
        assertNotNull(found);
        assertEquals("user001", found.getUserId());
        assertTrue(found.isAuthenticated());
        ch.close();
    }

    @Test
    void bindUserCreatesSessionIfNotExists() {
        EmbeddedChannel ch = new EmbeddedChannel();
        manager.bindUser(ch, "user002");

        IConnectionSession found = manager.getByUserId("user002");
        assertNotNull(found);
        assertEquals(ch, found.getChannel());
        ch.close();
    }

    @Test
    void rebindKicksOldSession() {
        // 设置 KICK_OLD 策略（测试踢旧逻辑）
        manager.setLoginStrategy(MultiLoginStrategy.KICK_OLD);

        EmbeddedChannel oldCh = new EmbeddedChannel();
        IConnectionSession oldSession = manager.bindUser(oldCh, "user003");
        // First bind returns null since no previous binding
        assertNull(oldSession);
        assertEquals(1, manager.allSessions().size());

        EmbeddedChannel newCh = new EmbeddedChannel();
        IConnectionSession kicked = manager.bindUser(newCh, "user003");

        assertNotNull(kicked);
        assertEquals(oldCh, kicked.getChannel());

        // 旧 session 从 manager 中移除（只有新 session 保留）
        assertEquals(1, manager.allSessions().size(),
                "only the new session should remain");

        // 新 session 可用
        IConnectionSession found = manager.getByUserId("user003");
        assertEquals(newCh, found.getChannel());
        assertEquals("user003", found.getUserId());

        oldCh.close();
        newCh.close();
    }

    @Test
    void scanIdleClosesUnauthenticatedSessions() {
        EmbeddedChannel ch1 = new EmbeddedChannel();
        manager.createSession(ch1); // 未认证

        EmbeddedChannel ch2 = new EmbeddedChannel();
        manager.createSession(ch2);
        manager.bindUser(ch2, "user004"); // 已认证

        int closed = manager.scanIdleSessions(0); // 0 秒 = 立即超时
        assertEquals(1, closed);
        assertFalse(ch1.isActive());
        assertTrue(ch2.isActive());

        ch1.close();
        ch2.close();
    }

    @Test
    void allSessions() {
        assertEquals(0, manager.allSessions().size(), "should start empty");

        EmbeddedChannel ch1 = new EmbeddedChannel();
        IConnectionSession s1 = manager.createSession(ch1);
        assertNotNull(s1);
        assertEquals(1, manager.allSessions().size(), "after first session");

        EmbeddedChannel ch2 = new EmbeddedChannel();
        IConnectionSession s2 = manager.createSession(ch2);
        assertNotNull(s2);
        assertEquals(2, manager.allSessions().size(), "after second session");

        ch1.close();
        ch2.close();
    }

    @Test
    void clearClosesAllSessions() {
        EmbeddedChannel ch1 = new EmbeddedChannel();
        EmbeddedChannel ch2 = new EmbeddedChannel();
        manager.createSession(ch1);
        manager.createSession(ch2);

        manager.clear();

        assertEquals(0, manager.allSessions().size());
        assertNull(manager.getByChannel(ch1));
        assertNull(manager.getByChannel(ch2));
        ch1.close();
        ch2.close();
    }

    @Test
    void getByUserIdReturnsNullForUnknown() {
        assertNull(manager.getByUserId("nonexistent"));
    }
}
