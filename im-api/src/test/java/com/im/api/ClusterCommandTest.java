package com.im.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ClusterCommandTest {

    @Test
    void createsKickSessionCommandMessage() {
        ClusterCommand command = ClusterCommand.kickSession("u1", PlatformID.IOS, "s1", "SAME_TERM_KICK");
        ClusterMessage message = ClusterMessage.fromCommand("node-a", command);

        assertEquals(ClusterMessageKind.CLUSTER_COMMAND, message.getKind());
        assertEquals("CLUSTER_COMMAND", message.getTopic());
        assertEquals("u1", message.getCommand().userId());
        assertEquals(PlatformID.IOS, message.getCommand().platformId());
        assertEquals("s1", message.getCommand().sessionId());
        assertNull(message.getMessage());
    }
}
