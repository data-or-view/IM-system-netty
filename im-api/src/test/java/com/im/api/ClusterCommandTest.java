package com.im.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ClusterCommandTest {

    @Test
    void createsKickSessionCommandMessage() {
        ClusterCommand command = ClusterCommand.kickSession("u1", PlatformID.IOS, "s1", "SAME_TERM_KICK");
        ClusterMessage message = ClusterMessage.fromCommand("node-a", command);

        assertEquals(ClusterMessageKind.CLUSTER_COMMAND, message.getKind());
        assertEquals(ClusterMessageTopics.CLUSTER_COMMAND, message.getTopic());
        assertEquals("u1", message.getCommand().userId());
        assertEquals(PlatformID.IOS, message.getCommand().platformId());
        assertEquals("s1", message.getCommand().sessionId());
        assertNull(message.getMessage());
    }

    @Test
    void missingRequiredFieldsThrowValidationException() {
        var missingType = assertThrows(com.im.common.exception.ValidationException.class,
                () -> new ClusterCommand(null, "u1", ClusterCommand.ANY_PLATFORM_ID,
                        ClusterCommand.DEFAULT_SESSION_ID, "reason"));
        assertEquals("type is required", missingType.getDetail());

        var missingUserId = assertThrows(com.im.common.exception.ValidationException.class,
                () -> ClusterCommand.kickUser(" ", "reason"));
        assertEquals("userId is required", missingUserId.getDetail());
    }
}
