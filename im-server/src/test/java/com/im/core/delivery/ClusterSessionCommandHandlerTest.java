package com.im.core.delivery;

import com.im.api.ClusterCommand;
import com.im.api.ClusterMessage;
import com.im.api.PlatformID;
import com.im.core.session.NettyConnectionRef;
import com.im.core.session.SessionManager;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClusterSessionCommandHandlerTest {

    @Test
    void closesOnlyMatchingSessionForKickSessionCommand() {
        SessionManager sessionManager = new SessionManager();
        EmbeddedChannel phone = new EmbeddedChannel();
        EmbeddedChannel desktop = new EmbeddedChannel();
        NettyConnectionRef phoneRef = new NettyConnectionRef(phone);
        NettyConnectionRef desktopRef = new NettyConnectionRef(desktop);

        String phoneSessionId = sessionManager.createSession(phoneRef).getSessionId();
        sessionManager.bindUser(phoneRef.connectionId(), "u1", PlatformID.IOS);
        sessionManager.createSession(desktopRef);
        sessionManager.bindUser(desktopRef.connectionId(), "u1", PlatformID.WINDOWS);

        ClusterSessionCommandHandler handler = new ClusterSessionCommandHandler(sessionManager);
        handler.handle(ClusterMessage.fromCommand(
                "node-a",
                ClusterCommand.kickSession("u1", PlatformID.IOS, phoneSessionId, "SAME_TERM_KICK")));

        assertFalse(phone.isActive(), "matching session should be kicked");
        assertTrue(desktop.isActive(), "other platform session should stay online");

        sessionManager.clear();
    }
}
