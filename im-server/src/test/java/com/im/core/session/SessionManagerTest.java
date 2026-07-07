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
        NettyConnectionRef ref = ref(ch);
        IConnectionSession session = manager.createSession(ref);

        assertNotNull(session);
        assertNotNull(session.getSessionId());
        assertFalse(session.isAuthenticated());
        assertEquals(ref, session.getConnection());
        ch.close();
    }

    @Test
    void getByConnectionId() {
        EmbeddedChannel ch = new EmbeddedChannel();
        NettyConnectionRef ref = ref(ch);
        IConnectionSession created = manager.createSession(ref);

        IConnectionSession found = manager.getByConnectionId(ref.connectionId());
        assertNotNull(found);
        assertEquals(created.getSessionId(), found.getSessionId());
        ch.close();
    }

    @Test
    void removeSession() {
        EmbeddedChannel ch = new EmbeddedChannel();
        NettyConnectionRef ref = ref(ch);
        manager.createSession(ref);
        assertNotNull(manager.getByConnectionId(ref.connectionId()));

        manager.removeSession(ref.connectionId());
        assertNull(manager.getByConnectionId(ref.connectionId()));
        ch.close();
    }

    @Test
    void bindUserThenGetByUserId() {
        EmbeddedChannel ch = new EmbeddedChannel();
        NettyConnectionRef ref = ref(ch);
        manager.createSession(ref);
        manager.bindUser(ref.connectionId(), "user001");

        IConnectionSession found = manager.getByUserId("user001");
        assertNotNull(found);
        assertEquals("user001", found.getUserId());
        assertTrue(found.isAuthenticated());
        ch.close();
    }

    @Test
    void bindUserRequiresExistingConnection() {
        assertThrows(IllegalArgumentException.class,
                () -> manager.bindUser("missing-connection", "user002"));
    }

    @Test
    void rebindKicksOldSession() {
        manager.setLoginStrategy(MultiLoginStrategy.KICK_OLD);

        EmbeddedChannel oldCh = new EmbeddedChannel();
        NettyConnectionRef oldRef = ref(oldCh);
        manager.createSession(oldRef);
        IConnectionSession oldSession = manager.bindUser(oldRef.connectionId(), "user003");
        assertNull(oldSession);
        assertEquals(1, manager.allSessions().size());

        EmbeddedChannel newCh = new EmbeddedChannel();
        NettyConnectionRef newRef = ref(newCh);
        manager.createSession(newRef);
        IConnectionSession kicked = manager.bindUser(newRef.connectionId(), "user003");

        assertNotNull(kicked);
        assertEquals(oldRef, kicked.getConnection());
        assertEquals(1, manager.allSessions().size(), "only the new session should remain");

        IConnectionSession found = manager.getByUserId("user003");
        assertEquals(newRef, found.getConnection());
        assertEquals("user003", found.getUserId());

        oldCh.close();
        newCh.close();
    }

    @Test
    void scanIdleClosesUnauthenticatedSessions() {
        EmbeddedChannel ch1 = new EmbeddedChannel();
        manager.createSession(ref(ch1));

        EmbeddedChannel ch2 = new EmbeddedChannel();
        NettyConnectionRef ref2 = ref(ch2);
        manager.createSession(ref2);
        manager.bindUser(ref2.connectionId(), "user004");

        int closed = manager.scanIdleSessions(0);
        assertEquals(1, closed);
        assertFalse(ch1.isActive());
        assertTrue(ch2.isActive());

        ch1.close();
        ch2.close();
    }

    @Test
    void rejectNewDoesNotAuthenticateRejectedSession() {
        manager.setLoginStrategy(MultiLoginStrategy.REJECT_NEW);

        EmbeddedChannel oldCh = new EmbeddedChannel();
        NettyConnectionRef oldRef = ref(oldCh);
        manager.createSession(oldRef);
        manager.bindUser(oldRef.connectionId(), "user005");

        EmbeddedChannel newCh = new EmbeddedChannel();
        NettyConnectionRef newRef = ref(newCh);
        IConnectionSession newSession = manager.createSession(newRef);

        assertNull(manager.bindUser(newRef.connectionId(), "user005"));
        assertFalse(newSession.isAuthenticated());
        assertNull(newSession.getUserId());
        assertEquals(1, manager.getSessionsByUserId("user005").size());

        oldCh.close();
        newCh.close();
    }

    @Test
    void allSessions() {
        assertEquals(0, manager.allSessions().size(), "should start empty");

        EmbeddedChannel ch1 = new EmbeddedChannel();
        IConnectionSession s1 = manager.createSession(ref(ch1));
        assertNotNull(s1);
        assertEquals(1, manager.allSessions().size(), "after first session");

        EmbeddedChannel ch2 = new EmbeddedChannel();
        IConnectionSession s2 = manager.createSession(ref(ch2));
        assertNotNull(s2);
        assertEquals(2, manager.allSessions().size(), "after second session");

        ch1.close();
        ch2.close();
    }

    @Test
    void clearClosesAllSessions() {
        EmbeddedChannel ch1 = new EmbeddedChannel();
        EmbeddedChannel ch2 = new EmbeddedChannel();
        NettyConnectionRef ref1 = ref(ch1);
        NettyConnectionRef ref2 = ref(ch2);
        manager.createSession(ref1);
        manager.createSession(ref2);

        manager.clear();

        assertEquals(0, manager.allSessions().size());
        assertNull(manager.getByConnectionId(ref1.connectionId()));
        assertNull(manager.getByConnectionId(ref2.connectionId()));
        ch1.close();
        ch2.close();
    }

    @Test
    void getByUserIdReturnsNullForUnknown() {
        assertNull(manager.getByUserId("nonexistent"));
    }

    private static NettyConnectionRef ref(EmbeddedChannel channel) {
        return new NettyConnectionRef(channel);
    }
}
